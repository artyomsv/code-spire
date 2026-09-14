package dev.codespire.worksource.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.context.gitlab.*;
import dev.codespire.contract.scm.ForgeOrigin;
import dev.codespire.http.PinnedJsonResponse;
import dev.codespire.http.PinnedJsonWriter;
import dev.codespire.worksource.*;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;

/** Project-scoped GitLab issue evidence; label definitions and issue authors grant no authority. */
public final class GitLabWorkSource implements WorkSource {
    private final GitLabIssueClient read;
    private final PinnedJsonWriter write;
    private final ObjectMapper mapper;
    private final String origin;
    private final String base;
    private final Scope scope;
    public record Scope(String projectId, String name, URI htmlUrl) {}

    public GitLabWorkSource(GitLabIssueConfig config, ObjectMapper mapper, String projectId, String name) {
        this.read = new GitLabIssueClient(config, mapper);
        this.write = GitLabApiConnection.writer(config, mapper);
        this.mapper = mapper;
        this.base = config.baseUrl().replaceAll("/+$", "");
        this.origin = ForgeOrigin.of(base);
        this.scope = resolveScope(read, name);
        if (!scope.projectId().equals(projectId)) throw failure("Project identity changed; repair this source.");
    }

    public static Scope resolveScope(GitLabIssueConfig config, ObjectMapper mapper, String name) {
        return resolveScope(new GitLabIssueClient(config, mapper), name);
    }
    private static Scope resolveScope(GitLabIssueClient read, String name) {
        if (name == null || !name.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+")
                || Arrays.stream(name.split("/")).anyMatch(s -> s.equals(".") || s.equals("..")))
            throw failure("Select a GitLab namespace/project scope.");
        JsonNode project = evidence(read, "/api/v4/projects/" + GitLabIssueClient.encodePath(name)).body();
        if (!name.equals(project.path("path_with_namespace").asText())) throw failure("Project scope did not match.");
        URI link = URI.create(project.path("web_url").asText());
        if (!Set.of("http", "https").contains(link.getScheme()) || link.getHost() == null || link.getUserInfo() != null)
            throw failure("Invalid project URL.");
        return new Scope(id(project.path("id")), name, link);
    }
    @Override public Set<Capability> capabilities() { return Set.of(Capability.values()); }
    @Override public String capabilityDetail() { return "Polling and authenticated issue webhooks are supported."; }

    @Override public WorkPage<WorkIssueLocation> candidates(String cursor) {
        int page = page(cursor);
        String path = projectPath() + "/issues";
        PinnedJsonResponse response = evidence(read, path + "?state=all&scope=all&order_by=created_at&sort=asc&per_page=100&page=" + page);
        requireArray(response.body());
        List<WorkIssueLocation> result = new ArrayList<>();
        for (JsonNode issue : response.body()) result.add(location(issue));
        return new WorkPage<>(result, next(response, path, page));
    }
    @Override public WorkIssueLocation resolve(String issueKey) {
        if (issueKey == null || !issueKey.matches("[1-9][0-9]*")) throw failure("Enter an issue number in this source.");
        WorkIssueLocation found = location(evidence(read, projectPath() + "/issues/" + issueKey).body());
        if (!issueKey.equals(found.issueKey())) throw failure("The resolved issue number changed.");
        return found;
    }

    @Override public Fetch fetch(WorkIssueLocation issue) {
        try {
            requireIssue(issue);
            JsonNode node = evidence(read, issuePath(issue)).body();
            WorkIssueLocation found = location(node);
            if (!found.ref().equals(issue.ref())) return new Fetch.Unavailable("The issue identity changed.");
            requireArray(node.path("labels"));
            Set<String> labels = new HashSet<>();
            for (JsonNode label : node.path("labels")) {
                if (!label.isTextual() || label.asText().isBlank()) throw failure("Malformed current labels.");
                labels.add(label.asText());
            }
            return new Fetch.Found(new WorkTicket(found, node.path("title").asText(""), node.path("description").asText(""),
                    node.path("state").asText(""), labels));
        } catch (RuntimeException unavailable) {
            // Private/confidential issues can be hidden by 404; token failures never prove deletion.
            return new Fetch.Unavailable("GitLab ticket unavailable; check the account and project scope.");
        }
    }
    @Override public WorkPage<LabelEvent> labelEvents(WorkIssueLocation issue, String cursor) {
        requireIssue(issue);
        int page = page(cursor);
        String path = issuePath(issue) + "/resource_label_events";
        PinnedJsonResponse response = evidence(read, path + "?per_page=100&page=" + page);
        requireArray(response.body());
        List<LabelEvent> events = new ArrayList<>();
        for (JsonNode event : response.body()) {
            if (!"Issue".equals(event.path("resource_type").asText()) || !issue.ref().issueId().equals(id(event.path("resource_id"))))
                throw failure("Label event belongs to another resource.");
            String action = event.path("action").asText();
            if (!Set.of("add", "remove").contains(action)) throw failure("Unknown label action.");
            String label = event.path("label").path("name").asText("");
            if (label.isBlank()) throw failure("Missing label name.");
            String actor = event.path("user").isNull() || event.path("user").isMissingNode() ? null : id(event.path("user").path("id"));
            events.add(new LabelEvent(id(event.path("id")), issue.ref(), label,
                    action.equals("add") ? LabelEvent.Action.ADD : LabelEvent.Action.REMOVE, actor,
                    Instant.parse(event.path("created_at").asText()), null, LabelEvent.Origin.AUDIT_TRAIL));
        }
        return new WorkPage<>(events, next(response, path, page));
    }

    @Override public String comment(WorkIssueLocation issue, String text, String effectId) {
        requireFetched(issue);
        String body = WorkEffectMarker.body(text, effectId);
        String found = findComment(issue, text, effectId);
        return found == null ? id(post(issuePath(issue) + "/notes", json(Map.of("body", body))).path("id")) : found;
    }
    @Override public String findComment(WorkIssueLocation issue, String text, String effectId) {
        requireFetched(issue);
        String body = WorkEffectMarker.body(text, effectId);
        String path = issuePath(issue) + "/notes";
        String cursor = null;
        // An incomplete search is an unavailable write, never permission to duplicate a comment.
        for (int pages = 0; pages < 20; pages++) {
            int page = page(cursor);
            PinnedJsonResponse response = evidence(read, path + "?sort=asc&order_by=created_at&per_page=100&page=" + page);
            requireArray(response.body());
            for (JsonNode note : response.body()) if (body.equals(note.path("body").asText())) return id(note.path("id"));
            cursor = next(response, path, page);
            if (cursor == null) return null;
        }
        throw failure("Comment recovery search is incomplete; no write was sent.");
    }
    @Override public boolean transitionApplied(WorkIssueLocation issue, String transitionId, String effectId) {
        WorkEffectMarker.of(effectId);
        if (!Set.of("close", "reopen").contains(transitionId)) throw failure("GitLab transitions are close or reopen.");
        return (transitionId.equals("close") ? "closed" : "opened").equals(requireFetched(issue).trackerStatus());
    }
    @Override public void transition(WorkIssueLocation issue, String transitionId, String effectId) {
        WorkEffectMarker.of(effectId);
        if (!Set.of("close", "reopen").contains(transitionId)) throw failure("GitLab transitions are close or reopen.");
        WorkTicket ticket = requireFetched(issue);
        String target = transitionId.equals("close") ? "closed" : "opened";
        if (target.equals(ticket.trackerStatus())) return;
        JsonNode ack = put(issuePath(issue), json(Map.of("state_event", transitionId)));
        if (!issue.ref().issueId().equals(id(ack.path("id"))) || !target.equals(ack.path("state").asText()))
            throw failure("Transition acknowledgement did not match the issue and state.");
    }
    private WorkTicket requireFetched(WorkIssueLocation issue) {
        if (fetch(issue) instanceof Fetch.Found found) return found.ticket();
        throw failure("The issue identity could not be refreshed; no write was sent.");
    }
    private String json(Object body) {
        try { return mapper.writeValueAsString(body); }
        catch (Exception invalid) { throw failure("Invalid tracker effect."); }
    }
    private JsonNode post(String path, String body) {
        try { return write.post(path,body); }
        catch (RuntimeException unknown) { throw failure("Tracker write outcome is unknown."); }
    }
    private JsonNode put(String path, String body) {
        try { return write.put(path,body); }
        catch (RuntimeException unknown) { throw failure("Tracker write outcome is unknown."); }
    }
    private WorkIssueLocation location(JsonNode node) {
        if (!scope.projectId().equals(id(node.path("project_id")))) throw failure("Issue project did not match.");
        String number = id(node.path("iid"));
        return new WorkIssueLocation(new WorkIssueRef(WorkSourceType.GITLAB, origin, scope.projectId(), id(node.path("id"))), number,
                URI.create(scope.htmlUrl().toString().replaceAll("/+$", "") + "/-/issues/" + number));
    }
    private void requireIssue(WorkIssueLocation issue) {
        if (issue == null || issue.ref().type() != WorkSourceType.GITLAB || !origin.equals(issue.ref().origin())
                || !scope.projectId().equals(issue.ref().projectId())) throw failure("Issue is outside this work source.");
        page(issue.issueKey());
    }
    private String projectPath() { return "/api/v4/projects/" + scope.projectId(); }
    private String issuePath(WorkIssueLocation issue) { return projectPath() + "/issues/" + issue.issueKey(); }
    private String next(PinnedJsonResponse response, String path, int current) {
        List<String> values = response.headers().getOrDefault("x-next-page", List.of());
        if (values.size() > 1) throw failure("Ambiguous pagination.");
        String header = values.isEmpty() ? null : values.getFirst();
        String linked = null;
        var pattern = Pattern.compile("<([^>]+)>;\\s*rel=\"([^\"]+)\"");
        for (String links : response.headers().getOrDefault("link", List.of())) for (String part : links.split(",")) {
            var match = pattern.matcher(part.trim());
            if (!match.matches()) throw failure("Malformed pagination link.");
            if (!List.of(match.group(2).split(" ")).contains("next")) continue;
            if (linked != null) throw failure("Duplicate pagination link.");
            URI target = URI.create(base).resolve(match.group(1));
            if (target.getUserInfo() != null || target.getFragment() != null || target.getRawQuery() == null
                    || !origin.equals(ForgeOrigin.of(target.getScheme() + "://" + target.getRawAuthority()))
                    || !(URI.create(base).getPath() + path).equals(target.getPath())) throw failure("Pagination left project scope.");
            for (String parameter : target.getRawQuery().split("&")) if (parameter.startsWith("page=")) {
                if (linked != null) throw failure("Ambiguous page number.");
                linked = parameter.substring(5);
            }
            if (linked == null) throw failure("Pagination has no page.");
        }
        if (header != null && linked != null && !header.equals(linked)) throw failure("Pagination headers disagree.");
        String next = header == null ? linked : header;
        if (next == null) {
            if (response.body().size() >= 100) throw failure("A full page has no completion evidence.");
            return null;
        }
        if (next.isEmpty()) return null;
        if (page(next) != current + 1) throw failure("Pagination has a gap or cycle.");
        return next;
    }
    private static int page(String value) {
        if (value == null) return 1;
        try { int number = Integer.parseInt(value); if (number > 0 && value.equals(Integer.toString(number))) return number; }
        catch (NumberFormatException invalid) { /* Refuse noncanonical coordinates. */ }
        throw failure("Invalid page or issue coordinate.");
    }
    private static String id(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 1) throw failure("Missing stable GitLab identity.");
        return node.asText();
    }
    private static void requireArray(JsonNode node) { if (!node.isArray()) throw failure("Expected a complete array response."); }
    private static dev.codespire.http.PinnedJsonResponse evidence(GitLabIssueClient client, String path) {
        try { return client.getEvidence(path); }
        catch (RuntimeException unavailable) { throw failure("Tracker evidence is unavailable; check the selected account and scope."); }
    }
    private static WorkSourceException failure(String reason) { return new WorkSourceException(reason); }
}
