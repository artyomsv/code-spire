package dev.codespire.scm.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.CloseReason;
import dev.codespire.contract.event.IntegrationEvent.ManualCommandReceived;
import dev.codespire.contract.event.IntegrationEvent.PrAction;
import dev.codespire.contract.event.IntegrationEvent.PullRequestClosed;
import dev.codespire.contract.event.IntegrationEvent.PullRequestEventReceived;
import dev.codespire.contract.port.RawWebhook;
import dev.codespire.contract.port.ScmIngress;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.DiffRefs;
import dev.codespire.contract.scm.RepoRef;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * GitLab webhook ingress (SCM-MAPPING §7). GitLab does NOT sign the body: it echoes
 * the configured secret verbatim in the {@code X-Gitlab-Token} header, so
 * {@link #verifySignature} is a constant-time compare of that header against the
 * per-repo secret — the token IS the shared secret, and there is no HMAC over the
 * body to check. Translation turns Merge Request and Note (comment) hooks into
 * integration events. The self-loop guard (dropping bot-authored events, ADR-013)
 * runs downstream in the orchestrator; each event carries the acting user's stable
 * numeric id as {@code providerUserId} so the guard can match it.
 *
 * <p>Takes the per-repo webhook secret directly, not a {@link GitLabConfig}: the
 * ingress needs no API token or base URL — the internet-facing gateway holds neither.
 */
public class GitLabIngress implements ScmIngress {

    private static final String MERGE_REQUEST = "merge_request";
    private static final String NOTE = "note";
    private static final String MR_NOTEABLE = "MergeRequest";

    private final String webhookSecret;
    private final ObjectMapper mapper;
    private final Set<String> commands;

    public GitLabIngress(String webhookSecret, ObjectMapper mapper, Set<String> commands) {
        this.webhookSecret = webhookSecret;
        this.mapper = mapper;
        this.commands = Set.copyOf(commands);
    }

    @Override
    public ScmType type() {
        return ScmType.GITLAB;
    }

    /**
     * GitLab sends the raw secret in {@code X-Gitlab-Token} (no HMAC). Compare it to
     * the configured secret in constant time so a mismatch leaks no timing signal.
     */
    @Override
    public boolean verifySignature(RawWebhook raw) {
        // Defense in depth: a blank configured secret must never authenticate — else an
        // empty X-Gitlab-Token would match it (the token IS the secret, no HMAC).
        if (webhookSecret == null || webhookSecret.isBlank()) {
            return false;
        }
        String token = header(raw, "x-gitlab-token");
        if (token == null) {
            return false;
        }
        return MessageDigest.isEqual(
                token.getBytes(StandardCharsets.UTF_8),
                webhookSecret.getBytes(StandardCharsets.UTF_8)); // constant-time
    }

    @Override
    public List<IntegrationEvent> translate(RawWebhook raw) {
        JsonNode payload = parse(raw.body());
        return switch (payload.path("object_kind").asText("")) {
            case MERGE_REQUEST -> mergeRequest(payload);
            case NOTE -> note(payload);
            default -> List.of(); // push, pipeline, system hooks, ... are no-ops
        };
    }

    private List<IntegrationEvent> mergeRequest(JsonNode payload) {
        JsonNode attrs = payload.path("object_attributes");
        return switch (attrs.path("action").asText("")) {
            case "open", "reopen" -> prEvent(payload, attrs, PrAction.OPENED);
            // A bare "update" also fires on label/description edits; only a push (which
            // GitLab flags by including oldrev) moves the diff and warrants a re-review.
            case "update" -> attrs.has("oldrev") ? prEvent(payload, attrs, PrAction.UPDATED) : List.of();
            case "close" -> List.of(new PullRequestClosed(repo(payload), iid(attrs), CloseReason.DECLINED));
            case "merge" -> List.of(new PullRequestClosed(repo(payload), iid(attrs), CloseReason.MERGED));
            default -> List.of(); // approved / unapproved / ...
        };
    }

    private List<IntegrationEvent> prEvent(JsonNode payload, JsonNode attrs, PrAction action) {
        return List.of(new PullRequestEventReceived(
                repo(payload),
                iid(attrs),
                action,
                attrs.path("title").asText(""),
                attrs.path("description").asText(""),
                attrs.path("source_branch").asText(""),
                attrs.path("target_branch").asText(""),
                DiffRefs.headOnly(attrs.path("last_commit").path("id").asText("")),
                author(payload.path("user")),
                attrs.path("url").asText(""),
                type().providerType()));
    }

    /**
     * A merge-request comment. Only "/command" notes matter (a registered command
     * becomes ManualCommandReceived; the saga maps "review" -> force review). Notes on
     * issues/commits/snippets are ignored via {@code noteable_type}, and non-command
     * replies are not emitted (AuthorReplied is a parked roadmap item, so emitting them
     * would be dead output today). The MR number is {@code merge_request.iid}, not the
     * note's own id.
     */
    private List<IntegrationEvent> note(JsonNode payload) {
        JsonNode attrs = payload.path("object_attributes");
        if (!MR_NOTEABLE.equals(attrs.path("noteable_type").asText(""))) {
            return List.of();
        }
        String text = attrs.path("note").asText("").trim();
        if (!text.startsWith("/")) {
            return List.of();
        }
        String[] parts = text.substring(1).split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        if (!commands.contains(command)) {
            return List.of();
        }
        long iid = payload.path("merge_request").path("iid").asLong();
        return List.of(new ManualCommandReceived(repo(payload), iid, command,
                parts.length > 1 ? parts[1] : "", author(payload.path("user"))));
    }

    private RepoRef repo(JsonNode payload) {
        String path = payload.path("project").path("path_with_namespace").asText("");
        return GitLabProjectPath.parse(path).orElseThrow(() ->
                new IllegalArgumentException("Webhook payload has no valid project.path_with_namespace"));
    }

    private static long iid(JsonNode attrs) {
        return attrs.path("iid").asLong();
    }

    /** GitLab user object: numeric {@code id} is the stable identity the self-loop guard matches. */
    private static Author author(JsonNode user) {
        String username = user.path("username").asText("");
        return Author.of(user.path("id").asText(""), username, user.path("name").asText(username));
    }

    private JsonNode parse(byte[] body) {
        try {
            return mapper.readTree(body);
        } catch (IOException e) {
            throw new UncheckedIOException("Unparseable webhook payload", e);
        }
    }

    private static String header(RawWebhook raw, String name) {
        for (Map.Entry<String, String> e : raw.headers().entrySet()) {
            if (e.getKey().equalsIgnoreCase(name)) {
                return e.getValue();
            }
        }
        return null;
    }
}
