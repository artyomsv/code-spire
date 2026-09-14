package dev.codespire.worksource.jira;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.context.jira.*;
import dev.codespire.contract.scm.ForgeOrigin;
import dev.codespire.http.PinnedJsonWriter;
import dev.codespire.worksource.*;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/** Jira project mapping is independent of the target SCM. Only actual label deltas have authors. */
public final class JiraWorkSource implements WorkSource {
    private final JiraClient read;
    private final PinnedJsonWriter write;
    private final ObjectMapper mapper;
    private final String base, origin;
    private final Scope scope;
    private final boolean cloud;
    public record Scope(String projectId, String name) {}

    public JiraWorkSource(JiraConfig config, ObjectMapper mapper, String projectId, String name) {
        read = new JiraClient(config, mapper);
        write = JiraApiConnection.writer(config, mapper);
        this.mapper = mapper;
        base = config.baseUrl().replaceAll("/+$", ""); origin = ForgeOrigin.of(base);
        scope = resolveScope(read, name);
        if (!scope.projectId().equals(projectId)) throw failure("Project identity changed; repair this source.");
        String deployment = evidence(read, "/rest/api/2/serverInfo").body().path("deploymentType").asText();
        if (!Set.of("Cloud", "Server", "Data Center").contains(deployment)) throw failure("Jira deployment type is unavailable.");
        cloud = deployment.equals("Cloud");
    }
    public static Scope resolveScope(JiraConfig config, ObjectMapper mapper, String name) { return resolveScope(new JiraClient(config, mapper), name); }
    private static Scope resolveScope(JiraClient read, String name) {
        if (name == null || !name.matches("[A-Z][A-Z0-9_]{1,99}")) throw failure("Select a Jira project key.");
        JsonNode project = evidence(read, "/rest/api/2/project/" + name).body();
        if (!name.equals(project.path("key").asText())) throw failure("Project scope did not match.");
        return new Scope(id(project.path("id")), name);
    }
    @Override public Set<Capability> capabilities() {
        // The existing person directory confirms Cloud accountIds. DC user keys are not invented
        // from display names, and an expanded/truncated issue changelog is not a complete audit.
        return cloud ? Set.of(Capability.values()) : Set.of(Capability.CANDIDATES, Capability.COMMENT);
    }
    @Override public String capabilityDetail() {
        return "Jira uses polling. Cloud account IDs and complete label changelogs are required for attribution. "
                + "Deployments without LABEL_AUDIT support cannot attribute labels or recover transitions.";
    }
    @Override public WorkPage<WorkIssueLocation> candidates(String cursor) {
        String query = "jql=" + encode("project = " + scope.projectId() + " ORDER BY created ASC, key ASC") + "&fields=id,key,project&maxResults=100";
        JsonNode response;
        String next;
        if (cloud) {
            if (cursor != null && (cursor.isBlank() || cursor.length() > 4096)) throw failure("Invalid search cursor.");
            response = evidence(read, "/rest/api/2/search/jql?" + query + (cursor == null ? "" : "&nextPageToken=" + encode(cursor))).body();
            requireArray(response.path("issues"));
            if (!response.path("isLast").isBoolean()) throw failure("Search completion is unknown.");
            next = response.path("isLast").booleanValue() ? null : response.path("nextPageToken").asText("");
            if (next != null && (next.isBlank() || next.equals(cursor) || response.path("issues").isEmpty())) throw failure("Search cursor does not progress.");
        } else {
            int offset = offset(cursor);
            response = evidence(read, "/rest/api/2/search?" + query + "&startAt=" + offset).body();
            next = nextOffset(response, "issues", offset);
        }
        List<WorkIssueLocation> result = new ArrayList<>();
        for (JsonNode issue : response.path("issues")) result.add(location(issue));
        return new WorkPage<>(result, next);
    }

    @Override public boolean pollsActivities() {return cloud;}
    @Override public WorkPage<WorkSourceActivity> activities(WorkIssueLocation issue,String cursor) {
        if(!cloud)throw failure("Jira Data Center comment identities cannot be verified here.");
        requireIssue(issue);int offset=offset(cursor);
        var rows=evidence(read,issuePath(issue)+"/comment?startAt="+offset+"&maxResults=100&orderBy=created").body();
        String next=nextOffset(rows,"comments",offset);List<WorkSourceActivity> result=new ArrayList<>();
        for(var row:rows.path("comments")) {
            String actor=row.path("author").path("accountId").asText(null);
            if(!row.path("body").isTextual())throw failure("Jira comment text could not be verified.");
            var created=Instant.parse(row.path("created").asText().replaceFirst("([+-][0-9]{2})([0-9]{2})$", "$1:$2"));
            result.add(WorkSourceActivity.comment(id(row.path("id")),actor,row.path("body").asText()).at(created));
        }
        return new WorkPage<>(result,next);
    }
    @Override public WorkIssueLocation resolve(String issueKey) {
        if (issueKey == null || !issueKey.matches(java.util.regex.Pattern.quote(scope.name()) + "-[1-9][0-9]*"))
            throw failure("Enter an issue key in this source's project.");
        WorkIssueLocation found = location(evidence(read, "/rest/api/2/issue/" + issueKey + "?fields=project").body());
        if (!issueKey.equals(found.issueKey())) throw failure("The resolved issue key changed.");
        return found;
    }

    @Override public Fetch fetch(WorkIssueLocation issue) {
        try {
            requireIssue(issue);
            JsonNode response = evidence(read, issuePath(issue) + "?fields=project,summary,description,status,labels").body();
            WorkIssueLocation found = location(response);
            if (!found.ref().equals(issue.ref())) return new Fetch.Unavailable("The issue identity changed.");
            JsonNode fields = response.path("fields");
            requireArray(fields.path("labels"));
            Set<String> labels = new HashSet<>();
            for (JsonNode label : fields.path("labels")) {
                if (!label.isTextual() || label.asText().isBlank()) throw failure("Malformed current labels.");
                labels.add(label.asText());
            }
            JsonNode description = fields.path("description");
            if (!description.isMissingNode() && !description.isNull() && !description.isTextual()) throw failure("Unsupported Jira description format.");
            return new Fetch.Found(new WorkTicket(found, fields.path("summary").asText(""), description.asText(""),
                    fields.path("status").path("name").asText(""), labels));
        } catch (RuntimeException unavailable) { return new Fetch.Unavailable("Jira ticket unavailable; check the selected account and project scope."); }
    }
    @Override public WorkPage<LabelEvent> labelEvents(WorkIssueLocation issue, String cursor) {
        requireIssue(issue);
        if (!cloud) throw failure("Complete Jira Cloud account attribution is unsupported on this deployment.");
        int offset = offset(cursor);
        JsonNode response = changelog(issue, offset);
        String next = nextOffset(response, "values", offset);
        List<LabelEvent> result = new ArrayList<>();
        for (JsonNode history : response.path("values")) {
            requireArray(history.path("items"));
            for (JsonNode change : history.path("items")) {
                if (!"labels".equals(change.path("fieldId").asText(change.path("field").asText()))) continue;
                Set<String> before = labels(change.path("fromString")), after = labels(change.path("toString"));
                JsonNode actorId = history.path("author").path("accountId");
                String actor = actorId.isTextual() && !actorId.textValue().isBlank() ? actorId.textValue() : null;
                Instant time = Instant.parse(history.path("created").asText().replaceFirst("([+-][0-9]{2})([0-9]{2})$", "$1:$2"));
                String eventId = id(history.path("id"));
                for (String label : after) if (!before.contains(label)) result.add(event(issue, eventId, label, LabelEvent.Action.ADD, actor, time));
                for (String label : before) if (!after.contains(label)) result.add(event(issue, eventId, label, LabelEvent.Action.REMOVE, actor, time));
            }
        }
        return new WorkPage<>(result, next);
    }
    private LabelEvent event(WorkIssueLocation issue, String id, String label, LabelEvent.Action action, String actor, Instant time) {
        String unique = id + ":" + action + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(label.getBytes(StandardCharsets.UTF_8));
        return new LabelEvent(unique, issue.ref(), label, action, actor, time, null,
                actor == null ? LabelEvent.Origin.UNATTRIBUTED : LabelEvent.Origin.AUDIT_TRAIL);
    }
    private static Set<String> labels(JsonNode value) {
        if (value.isNull()) return Set.of();
        if (!value.isTextual()) throw failure("Label delta is unavailable.");
        if (value.asText().isEmpty()) return Set.of();
        Set<String> labels = new HashSet<>();
        // Jira Cloud's documented fromString/toString label sets use spaces. Retained labels
        // are not additions. An ambiguous/noncanonical serialization refuses the whole audit.
        for (String label : value.asText().split(" ", -1)) {
            if (label.isBlank() || label.chars().anyMatch(Character::isWhitespace) || !labels.add(label)) throw failure("Ambiguous label delta.");
        }
        return labels;
    }
    @Override public String comment(WorkIssueLocation issue, String text, String effectId) {
        requireFetched(issue);
        String body = WorkEffectMarker.body(text, effectId);
        String found = findComment(issue, text, effectId);
        return found == null ? id(post(issuePath(issue) + "/comment", json(Map.of("body", body))).path("id")) : found;
    }
    @Override public String findComment(WorkIssueLocation issue, String text, String effectId) {
        requireFetched(issue);
        String body = WorkEffectMarker.body(text, effectId), cursor = null;
        for (int pages = 0; pages < 20; pages++) {
            int offset = offset(cursor);
            JsonNode response = evidence(read, issuePath(issue) + "/comment?startAt=" + offset + "&maxResults=100&orderBy=created").body();
            cursor = nextOffset(response, "comments", offset);
            for (JsonNode comment : response.path("comments")) if (body.equals(comment.path("body").asText())) return id(comment.path("id"));
            if (cursor == null) return null;
        }
        throw failure("Comment recovery search is incomplete.");
    }
    @Override public void transition(WorkIssueLocation issue, String transitionId, String effectId) {
        requireFetched(issue); WorkEffectMarker.of(effectId);
        if (!cloud) throw failure("Recoverable Jira transitions are unavailable on this deployment.");
        if (transitionApplied(issue, transitionId, effectId)) return;
        JsonNode transitions = evidence(read, issuePath(issue) + "/transitions?expand=transitions.fields").body().path("transitions");
        requireArray(transitions);
        JsonNode selected = null;
        for (JsonNode transition : transitions) if (transitionId.equals(transition.path("id").asText())) {
            if (selected != null) throw failure("Ambiguous transition identity.");
            selected = transition;
        }
        if (selected == null) throw failure("Select an available Jira transition ID; status names are not commands.");
        if (!selected.path("fields").isObject()) throw failure("Transition requirements could not be established.");
        for (JsonNode field : selected.path("fields")) if (field.path("required").asBoolean() && !field.path("hasDefaultValue").asBoolean())
            throw failure("This Jira transition requires additional fields.");
        String target = id(selected.path("to").path("id"));
        postNoContent(issuePath(issue) + "/transitions", json(Map.of("transition", Map.of("id", transitionId),
                "historyMetadata", Map.of("type", "work-item", "extraData", Map.of("workEffectId", effectId, "workTransitionId", transitionId)))));
        JsonNode status = evidence(read, issuePath(issue) + "?fields=status").body();
        if (!issue.ref().issueId().equals(id(status.path("id"))) || !target.equals(id(status.path("fields").path("status").path("id"))))
            throw failure("Transition acknowledgement could not be confirmed.");
    }
    @Override public boolean transitionApplied(WorkIssueLocation issue, String transitionId, String effectId) {
        requireFetched(issue); WorkEffectMarker.of(effectId);
        if (!cloud) throw failure("Complete transition recovery audit is unavailable.");
        String cursor = null;
        for (int pages = 0; pages < 20; pages++) {
            int offset = offset(cursor);
            JsonNode response = changelog(issue, offset);
            cursor = nextOffset(response, "values", offset);
            for (JsonNode history : response.path("values")) {
                JsonNode extra = history.path("historyMetadata").path("extraData");
                if (effectId.equals(extra.path("workEffectId").asText()) && transitionId.equals(extra.path("workTransitionId").asText())) return true;
            }
            if (cursor == null) return false;
        }
        throw failure("Transition recovery audit is incomplete.");
    }
    private JsonNode changelog(WorkIssueLocation issue, int offset) { return evidence(read, issuePath(issue) + "/changelog?startAt=" + offset + "&maxResults=100").body(); }
    private void requireFetched(WorkIssueLocation issue) { if (!(fetch(issue) instanceof Fetch.Found)) throw failure("Issue identity could not be refreshed; no write was sent."); }
    private WorkIssueLocation location(JsonNode issue) {
        if (!scope.projectId().equals(id(issue.path("fields").path("project").path("id")))) throw failure("Issue is outside this Jira project.");
        String key = issue.path("key").asText();
        if (!key.matches(java.util.regex.Pattern.quote(scope.name()) + "-[1-9][0-9]*")) throw failure("Issue key is outside the project.");
        return new WorkIssueLocation(new WorkIssueRef(WorkSourceType.JIRA, origin, scope.projectId(), id(issue.path("id"))), key, URI.create(base + "/browse/" + key));
    }
    private void requireIssue(WorkIssueLocation issue) {
        if (issue == null || issue.ref().type() != WorkSourceType.JIRA || !origin.equals(issue.ref().origin())
                || !scope.projectId().equals(issue.ref().projectId()) || !issue.ref().issueId().matches("[1-9][0-9]*")) throw failure("Issue is outside this work source.");
    }
    private String issuePath(WorkIssueLocation issue) { return "/rest/api/2/issue/" + issue.ref().issueId(); }
    private static String nextOffset(JsonNode response, String field, int offset) {
        requireArray(response.path(field));
        if (!response.path("startAt").isIntegralNumber() || response.path("startAt").asLong() != offset
                || !response.path("total").isIntegralNumber() || !response.path("total").canConvertToInt()) throw failure("Pagination position is unknown.");
        int count = response.path(field).size(), total = response.path("total").intValue();
        long next = (long) offset + count;
        if (next > total || next == offset && next < total) throw failure("Pagination does not progress.");
        if (response.has("isLast") && (!response.path("isLast").isBoolean() || response.path("isLast").booleanValue() != (next == total)))
            throw failure("Pagination completion disagrees with total.");
        return next == total ? null : Long.toString(next);
    }
    private static int offset(String cursor) {
        if (cursor == null) return 0;
        try { int value = Integer.parseInt(cursor); if (value >= 0 && cursor.equals(Integer.toString(value))) return value; }
        catch (NumberFormatException invalid) { /* Refuse a malformed cursor. */ }
        throw failure("Invalid Jira pagination offset.");
    }
    private static String id(JsonNode node) {
        String value = node.asText("");
        if (!value.matches("[1-9][0-9]*")) throw failure("Missing stable Jira issue/project identity.");
        return value;
    }
    private static String encode(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8); }
    private String json(Object value) { try { return mapper.writeValueAsString(value); } catch (Exception invalid) { throw failure("Invalid tracker effect."); } }
    private JsonNode post(String path, String body) {
        try { return write.post(path,body); }
        catch (RuntimeException unknown) { throw failure("Tracker write outcome is unknown."); }
    }
    private void postNoContent(String path, String body) {
        try { write.postNoContent(path,body); }
        catch (RuntimeException unknown) { throw failure("Tracker write outcome is unknown."); }
    }
    private static void requireArray(JsonNode value) { if (!value.isArray()) throw failure("Expected a complete array response."); }
    private static dev.codespire.http.PinnedJsonResponse evidence(JiraClient client, String path) {
        try { return client.getEvidence(path); }
        catch (RuntimeException unavailable) { throw failure("Tracker evidence is unavailable; check the selected account and scope."); }
    }
    private static WorkSourceException failure(String reason) { return new WorkSourceException(reason); }
}
