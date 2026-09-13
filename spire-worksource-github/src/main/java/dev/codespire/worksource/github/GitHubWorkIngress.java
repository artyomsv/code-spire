package dev.codespire.worksource.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.port.RawWebhook;
import dev.codespire.contract.scm.ForgeOrigin;
import dev.codespire.scm.github.GitHubIngress;
import dev.codespire.worksource.*;
import java.net.URI;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Uses the existing production HMAC verifier; emits control facts without issue content. */
public final class GitHubWorkIngress implements WorkSourceIngress {
    private static final Pattern PART = Pattern.compile("(?!\\.{1,2}$)[A-Za-z0-9._-]+");
    private final GitHubIngress signatures;
    private final ObjectMapper mapper;
    public GitHubWorkIngress(String secret, ObjectMapper mapper) {
        this.signatures = new GitHubIngress(secret, mapper, Set.of());
        this.mapper = mapper;
    }

    @Override public List<WorkSourceSignal> translate(Map<String, String> headers, byte[] body, String configuredOrigin) {
        RawWebhook raw = new RawWebhook(headers, body);
        if (!signatures.verifySignature(raw)) throw new WorkSourceException("Invalid GitHub work-source signature.");
        String kind = headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("X-GitHub-Event"))
                .map(Map.Entry::getValue).findFirst().orElse("");
        if (!"issues".equals(kind)) return List.of();
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode repository = root.path("repository");
            JsonNode issue = root.path("issue");
            if (issue.has("pull_request")) return List.of();
            String scope = repository.path("full_name").asText("");
            String[] parts = scope.split("/", -1);
            if (parts.length != 2 || !PART.matcher(parts[0]).matches() || !PART.matcher(parts[1]).matches())
                throw new WorkSourceException("Invalid GitHub work-source scope.");
            WorkIssueRef ref = new WorkIssueRef(WorkSourceType.GITHUB, ForgeOrigin.of(configuredOrigin),
                    id(repository.path("id")), id(issue.path("id")));
            String number = id(issue.path("number"));
            // Link is a lookup/display hint until a scoped API read establishes the canonical ticket.
            URI link = URI.create(issue.path("html_url").asText());
            if ((!"http".equals(link.getScheme()) && !"https".equals(link.getScheme())) || link.getHost() == null
                    || link.getUserInfo() != null) throw new WorkSourceException("Invalid GitHub ticket URL.");
            WorkIssueLocation location = new WorkIssueLocation(ref, number, link);
            String action = root.path("action").asText();
            LabelEvent hint = null;
            if ("labeled".equals(action) || "unlabeled".equals(action)) {
                String label = root.path("label").path("name").asText("");
                if (label.isBlank()) throw new WorkSourceException("The label event has no label.");
                String actor = root.path("sender").isMissingNode() || root.path("sender").isNull()
                        ? null : id(root.path("sender").path("id"));
                String eventId = "webhook-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body));
                hint = new LabelEvent(eventId, ref, label, "labeled".equals(action) ? LabelEvent.Action.ADD : LabelEvent.Action.REMOVE,
                        actor, Instant.parse(issue.path("updated_at").asText()), null, LabelEvent.Origin.WEBHOOK);
            }
            return List.of(new WorkSourceSignal(scope, location, hint));
        } catch (Exception malformed) {
            throw new WorkSourceException("Malformed GitHub work-source control facts.");
        }
    }
    private static String id(JsonNode value) {
        if (!value.isIntegralNumber() || !value.canConvertToLong() || value.longValue() < 1)
            throw new WorkSourceException("Missing stable GitHub identity.");
        return value.asText();
    }
}
