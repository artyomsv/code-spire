package dev.codespire.worksource.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.context.github.GitHubIssueClient;
import dev.codespire.context.github.GitHubIssueConfig;
import dev.codespire.contract.scm.ForgeOrigin;
import dev.codespire.http.PinnedJsonResponse;
import dev.codespire.worksource.*;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Scoped issue reads and label audit. Profile selection belongs to the orchestrator. */
public final class GitHubWorkSource implements WorkSource {
    private static final Pattern PART = Pattern.compile("(?!\\.{1,2}$)[A-Za-z0-9._-]+");
    private static final Pattern LINK = Pattern.compile("<([^>]+)>;\\s*rel=\"([^\"]+)\"");
    private final GitHubIssueClient read;
    private final GitHubWorkWriter write;
    private final URI api;
    private final Scope scope;
    private final String origin;

    public record Scope(String projectId, String name, URI htmlUrl) {}

    public GitHubWorkSource(GitHubIssueConfig config, ObjectMapper mapper, String projectId, String name) {
        this.read = new GitHubIssueClient(config, mapper);
        this.api = URI.create(config.baseUrl().replaceAll("/$", ""));
        this.origin = ForgeOrigin.of(config.baseUrl());
        this.scope = resolveScope(read, name);
        if (!scope.projectId().equals(projectId)) throw unavailable("The repository identity changed; repair the work source.");
        this.write = new GitHubWorkWriter(config, mapper, scope.name());
    }

    public static Scope resolveScope(GitHubIssueConfig config, ObjectMapper mapper, String name) {
        return resolveScope(new GitHubIssueClient(config, mapper), name);
    }

    private static Scope resolveScope(GitHubIssueClient read, String name) {
        if (name == null) throw unavailable("Select a GitHub owner/repository scope.");
        String[] parts = name.split("/", -1);
        if (parts.length != 2 || !PART.matcher(parts[0]).matches() || !PART.matcher(parts[1]).matches())
            throw unavailable("Select a GitHub owner/repository scope.");
        try {
            JsonNode repository = read.getEvidence("/repos/" + name).body();
            if (!name.equalsIgnoreCase(repository.path("full_name").asText())) throw unavailable("Repository scope did not match.");
            return new Scope(id(repository.path("id")), name, httpUri(repository.path("html_url").asText()));
        } catch (RuntimeException failure) { throw unavailable("GitHub repository metadata is unavailable; check the selected account and scope."); }
    }

    @Override public Set<Capability> capabilities() {
        return Set.of(Capability.CANDIDATES, Capability.LABEL_AUDIT, Capability.COMMENT, Capability.TRANSITION);
    }
    @Override public String capabilityDetail() { return "Polling and authenticated issue webhooks are supported."; }

    @Override public WorkPage<WorkIssueLocation> candidates(String cursor) {
        String path = "/repos/" + scope.name() + "/issues";
        int page = page(cursor);
        try {
            PinnedJsonResponse response = read.getEvidence(path + "?state=open&sort=created&direction=asc&per_page=100&page=" + page);
            if (!response.body().isArray()) throw unavailable("Malformed issue listing.");
            List<WorkIssueLocation> candidates = new ArrayList<>();
            for (JsonNode issue : response.body()) {
                if (issue.has("pull_request")) continue;
                candidates.add(location(issue));
            }
            return new WorkPage<>(candidates, next(response, path, page));
        } catch (RuntimeException failure) { throw unavailable("GitHub issue candidates could not be read completely."); }
    }

    @Override public Fetch fetch(WorkIssueLocation issue) {
        try {
            requireIssue(issue);
            JsonNode response = read.getEvidence(issuePath(issue)).body();
            if (response.has("pull_request")) return new Fetch.Unavailable("The selected coordinate is a pull request, not a work ticket.");
            WorkIssueLocation found = location(response);
            if (!found.ref().equals(issue.ref())) return new Fetch.Unavailable("The issue identity or repository changed; explicit repair is required.");
            JsonNode labels = response.path("labels");
            if (!labels.isArray()) return new Fetch.Unavailable("Current issue labels could not be established.");
            Set<String> current = new HashSet<>();
            for (JsonNode label : labels) {
                String name = label.isTextual() ? label.asText() : label.path("name").asText("");
                if (name.isBlank()) return new Fetch.Unavailable("The current label response is malformed.");
                current.add(name);
            }
            return new Fetch.Found(new WorkTicket(found, response.path("title").asText(""), response.path("body").asText(""),
                    response.path("state").asText(""), current));
        } catch (RuntimeException failure) {
            // A 404 can hide a private issue; a 410 can mean disabled issues. Neither proves deletion.
            return new Fetch.Unavailable("GitHub ticket is unavailable or no longer matches this source; check account access and scope.");
        }
    }

    @Override public WorkPage<LabelEvent> labelEvents(WorkIssueLocation issue, String cursor) {
        requireIssue(issue);
        String path = issuePath(issue) + "/timeline";
        int page = page(cursor);
        try {
            PinnedJsonResponse response = read.getEvidence(path + "?per_page=100&page=" + page);
            if (!response.body().isArray()) throw unavailable("Malformed timeline.");
            List<LabelEvent> events = new ArrayList<>();
            for (JsonNode event : response.body()) {
                String kind = event.path("event").asText();
                if (!kind.equals("labeled") && !kind.equals("unlabeled")) continue;
                String label = event.path("label").path("name").asText("");
                if (label.isBlank()) throw unavailable("A label event has no label.");
                String actor = event.path("actor").isNull() || event.path("actor").isMissingNode()
                        ? null : id(event.path("actor").path("id"));
                events.add(new LabelEvent(id(event.path("id")), issue.ref(), label,
                        kind.equals("labeled") ? LabelEvent.Action.ADD : LabelEvent.Action.REMOVE, actor,
                        Instant.parse(event.path("created_at").asText()), null, LabelEvent.Origin.AUDIT_TRAIL));
            }
            return new WorkPage<>(events, next(response, path, page));
        } catch (RuntimeException failure) { throw unavailable("GitHub label audit is incomplete or unavailable; current appliers cannot be established."); }
    }

    @Override public String comment(WorkIssueLocation issue, String text, String effectId) {
        requireWritableIssue(issue);
        String found = findComment(issue,text,effectId);
        if (found != null) return found;
        return write.comment(issue.issueKey(), text, effectId);
    }
    @Override public String findComment(WorkIssueLocation issue, String text, String effectId) {
        try { return findCommentBody(issue,text,effectId); }
        catch (RuntimeException unavailable) { throw unavailable("GitHub comment recovery is unavailable; check the selected account and scope."); }
    }
    private String findCommentBody(WorkIssueLocation issue, String text, String effectId) {
        requireWritableIssue(issue);
        String body = WorkEffectMarker.body(text,effectId), cursor = null;
        String path = issuePath(issue) + "/comments";
        for (int pages = 0; pages < 20; pages++) {
            int page = page(cursor);
            PinnedJsonResponse response = read.getEvidence(path + "?per_page=100&page=" + page);
            if (!response.body().isArray()) throw unavailable("Comment recovery is incomplete.");
            for (JsonNode comment : response.body()) if (body.equals(comment.path("body").asText())) return id(comment.path("id"));
            cursor = next(response,path,page);
            if (cursor == null) return null;
        }
        throw unavailable("Comment recovery exceeded its page bound.");
    }
    @Override public boolean transitionApplied(WorkIssueLocation issue, String transitionId, String effectId) {
        WorkEffectMarker.of(effectId);
        if (!Set.of("open","closed").contains(transitionId)) throw unavailable("GitHub supports only open/closed transitions.");
        if (!(fetch(issue) instanceof Fetch.Found found)) throw unavailable("The issue could not be refreshed.");
        return transitionId.equals(found.ticket().trackerStatus());
    }
    @Override public void transition(WorkIssueLocation issue, String transitionId, String effectId) {
        requireWritableIssue(issue);
        if (transitionApplied(issue,transitionId,effectId)) return;
        write.transition(issue.issueKey(), issue.ref().issueId(), transitionId);
    }

    private void requireWritableIssue(WorkIssueLocation issue) {
        if (!(fetch(issue) instanceof Fetch.Found)) throw unavailable("The ticket identity could not be refreshed; no tracker write was sent.");
    }

    private WorkIssueLocation location(JsonNode issue) {
        if (!(api + "/repos/" + scope.name()).equals(issue.path("repository_url").asText())) throw unavailable("Issue repository scope did not match.");
        String number = id(issue.path("number"));
        WorkIssueRef ref = new WorkIssueRef(WorkSourceType.GITHUB, origin, scope.projectId(), id(issue.path("id")));
        return new WorkIssueLocation(ref, number, URI.create(scope.htmlUrl().toString().replaceAll("/$", "") + "/issues/" + number));
    }

    private void requireIssue(WorkIssueLocation issue) {
        if (issue == null || issue.ref() == null || issue.issueKey() == null || issue.ref().type() != WorkSourceType.GITHUB
                || !origin.equals(issue.ref().origin()) || !scope.projectId().equals(issue.ref().projectId()))
            throw unavailable("The issue is outside this work source.");
        page(issue.issueKey()); // canonical positive decimal path component; never caller-supplied URL text
    }

    private String issuePath(WorkIssueLocation issue) { return "/repos/" + scope.name() + "/issues/" + issue.issueKey(); }

    private String next(PinnedJsonResponse response, String path, int current) {
        List<String> headers = response.headers().getOrDefault("link", List.of());
        String found = null;
        for (String header : headers) for (String part : header.split(",")) {
            Matcher link = LINK.matcher(part.strip());
            if (!link.matches()) throw unavailable("Malformed pagination link.");
            if (!List.of(link.group(2).split(" ")).contains("next")) continue;
            if (found != null) throw unavailable("Ambiguous pagination link.");
            URI target = api.resolve(link.group(1));
            if (!origin.equals(ForgeOrigin.of(target.getScheme() + "://" + target.getRawAuthority())) || target.getUserInfo() != null || target.getFragment() != null
                    || !(api.getPath() + path).equals(target.getPath())) throw unavailable("Pagination left the registered scope.");
            String nextPage = null;
            if (target.getRawQuery() != null) for (String parameter : target.getRawQuery().split("&")) {
                if (parameter.startsWith("page=")) {
                    if (nextPage != null) throw unavailable("Ambiguous page number.");
                    nextPage = parameter.substring(5);
                }
            }
            if (nextPage == null || page(nextPage) != current + 1) throw unavailable("Pagination contains a gap or cycle.");
            found = nextPage;
        }
        return found;
    }

    private static int page(String cursor) {
        if (cursor == null) return 1;
        try {
            int page = Integer.parseInt(cursor);
            if (page < 1 || !Integer.toString(page).equals(cursor)) throw unavailable("Invalid issue/page coordinate.");
            return page;
        } catch (NumberFormatException failure) { throw unavailable("Invalid issue/page coordinate."); }
    }
    private static String id(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1) throw unavailable("Missing stable GitHub identity.");
        return value.asText();
    }
    private static URI httpUri(String value) {
        URI uri = URI.create(value);
        if ((!"https".equals(uri.getScheme()) && !"http".equals(uri.getScheme())) || uri.getHost() == null
                || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) throw unavailable("Invalid tracker URL.");
        return uri;
    }
    private static WorkSourceException unavailable(String reason) { return new WorkSourceException(reason); }
}
