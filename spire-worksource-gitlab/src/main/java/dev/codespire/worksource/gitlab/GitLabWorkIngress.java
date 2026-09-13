package dev.codespire.worksource.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.port.RawWebhook;
import dev.codespire.contract.scm.ForgeOrigin;
import dev.codespire.scm.gitlab.GitLabIngress;
import dev.codespire.worksource.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;

/** Existing constant-time GitLab token verification, followed by project-bound issue control facts. */
public final class GitLabWorkIngress implements WorkSourceIngress {
    private final GitLabIngress signatures;
    private final ObjectMapper mapper;
    public GitLabWorkIngress(String secret, ObjectMapper mapper) {
        signatures = new GitLabIngress(secret, mapper, Set.of()); this.mapper = mapper;
    }
    @Override public List<WorkSourceSignal> translate(Map<String,String> headers, byte[] body, String configuredOrigin) {
        if (!signatures.verifySignature(new RawWebhook(headers,body))) throw new WorkSourceException("Invalid GitLab work-source token.");
        String kind = headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("X-Gitlab-Event")).map(Map.Entry::getValue).findFirst().orElse("");
        if (!"Issue Hook".equals(kind)) return List.of();
        try {
            JsonNode root = mapper.readTree(body), project = root.path("project"), issue = root.path("object_attributes");
            if (!"issue".equals(root.path("object_kind").asText())) throw new IllegalArgumentException("Not an issue hook");
            String scope = project.path("path_with_namespace").asText();
            if (!scope.matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+")
                    || Arrays.stream(scope.split("/")).anyMatch(s -> s.equals(".") || s.equals(".."))) throw new IllegalArgumentException("Invalid scope");
            String projectId = id(project.path("id"));
            if (!projectId.equals(id(issue.path("project_id")))) throw new IllegalArgumentException("Issue project mismatch");
            WorkIssueRef ref = new WorkIssueRef(WorkSourceType.GITLAB, ForgeOrigin.of(configuredOrigin), projectId, id(issue.path("id")));
            String number = id(issue.path("iid"));
            WorkIssueLocation location = new WorkIssueLocation(ref, number, URI.create(ForgeOrigin.of(configuredOrigin) + "/" + scope + "/-/issues/" + number));
            JsonNode delta = root.path("changes").path("labels");
            if (delta.isMissingNode()) return List.of(new WorkSourceSignal(scope, location, null));
            Set<String> before = labels(delta.path("previous")), after = labels(delta.path("current"));
            String actor = root.path("user").isMissingNode() || root.path("user").isNull() ? null : id(root.path("user").path("id"));
            Instant time = Instant.parse(issue.path("updated_at").asText().replace(" UTC", "Z").replace(' ', 'T'));
            String delivery = "webhook-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
            List<WorkSourceSignal> result = new ArrayList<>();
            for (String label : after) if (!before.contains(label)) result.add(signal(scope,location,delivery,label,LabelEvent.Action.ADD,actor,time));
            for (String label : before) if (!after.contains(label)) result.add(signal(scope,location,delivery,label,LabelEvent.Action.REMOVE,actor,time));
            return result.isEmpty() ? List.of(new WorkSourceSignal(scope,location,null)) : List.copyOf(result);
        } catch (Exception invalid) { throw new WorkSourceException("Malformed GitLab work-source control facts."); }
    }
    private static WorkSourceSignal signal(String scope, WorkIssueLocation issue, String delivery, String label, LabelEvent.Action action, String actor, Instant time) {
        String eventId = delivery + ":" + action + ":" + Base64.getUrlEncoder().withoutPadding().encodeToString(label.getBytes(StandardCharsets.UTF_8));
        return new WorkSourceSignal(scope,issue,new LabelEvent(eventId,issue.ref(),label,action,actor,time,null,LabelEvent.Origin.WEBHOOK));
    }
    private static Set<String> labels(JsonNode array) {
        if (!array.isArray()) throw new IllegalArgumentException("Missing label delta");
        Set<String> labels = new TreeSet<>();
        for (JsonNode node : array) {
            String name = node.path("title").asText();
            if (name.isBlank()) throw new IllegalArgumentException("Missing label title"); labels.add(name);
        }
        return labels;
    }
    private static String id(JsonNode node) {
        if (!node.isIntegralNumber() || !node.canConvertToLong() || node.longValue() < 1) throw new IllegalArgumentException("Missing stable identity");
        return node.asText();
    }
}
