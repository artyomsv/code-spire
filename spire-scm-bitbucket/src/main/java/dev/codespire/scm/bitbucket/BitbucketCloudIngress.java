package dev.codespire.scm.bitbucket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.AuthorReplied;
import dev.codespire.contract.event.IntegrationEvent.CloseReason;
import dev.codespire.contract.event.IntegrationEvent.ManualCommandReceived;
import dev.codespire.contract.event.IntegrationEvent.PrAction;
import dev.codespire.contract.event.IntegrationEvent.PullRequestClosed;
import dev.codespire.contract.event.IntegrationEvent.PullRequestEventReceived;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.port.RawWebhook;
import dev.codespire.contract.port.ScmIngress;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.DiffRefs;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Bitbucket Cloud webhook ingress (CONTRACT §10, SCM-MAPPING §7):
 * X-Hub-Signature HMAC-SHA256 verification (constant-time compare),
 * "/command" parsing against the registered capability commands, and
 * translation into integration events. The self-loop guard (dropping
 * bot-authored events, ADR-013) runs downstream in the orchestrator, which
 * holds the registry's resolved bot account id; each event carries its author.
 */
public class BitbucketCloudIngress implements ScmIngress {

    private static final String HMAC_SHA256 = "HmacSHA256";

    private final ObjectMapper mapper;
    private final BitbucketCloudConfig config;
    private final Set<String> commands;

    public BitbucketCloudIngress(BitbucketCloudConfig config, ObjectMapper mapper, Set<String> commands) {
        this.config = config;
        this.mapper = mapper;
        this.commands = Set.copyOf(commands);
    }

    @Override
    public ScmType type() {
        return ScmType.BITBUCKET_CLOUD;
    }

    @Override
    public boolean verifySignature(RawWebhook raw) {
        String header = header(raw, "x-hub-signature");
        if (header == null || !header.startsWith("sha256=")) {
            return false;
        }
        byte[] expected;
        try {
            expected = HexFormat.of().parseHex(header.substring("sha256=".length()));
        } catch (IllegalArgumentException malformedHex) {
            return false;
        }
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(config.webhookSecret().getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] actual = mac.doFinal(raw.body());
            return MessageDigest.isEqual(expected, actual); // constant-time
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    @Override
    public List<IntegrationEvent> translate(RawWebhook raw) {
        String eventKey = header(raw, "x-event-key");
        if (eventKey == null) {
            return List.of();
        }
        JsonNode payload = parse(raw.body());
        return switch (eventKey) {
            case "pullrequest:created" -> pullRequestEvent(payload, PrAction.OPENED);
            case "pullrequest:updated" -> pullRequestEvent(payload, PrAction.UPDATED);
            case "pullrequest:fulfilled" -> closed(payload, CloseReason.MERGED);
            case "pullrequest:rejected" -> closed(payload, CloseReason.DECLINED);
            case "pullrequest:comment_created" -> comment(payload);
            default -> List.of();
        };
    }

    private List<IntegrationEvent> pullRequestEvent(JsonNode payload, PrAction action) {
        JsonNode pr = payload.path("pullrequest");
        return List.of(new PullRequestEventReceived(
                repo(payload),
                pr.path("id").asLong(),
                action,
                pr.path("title").asText(""),
                pr.path("description").asText(""),
                pr.path("source").path("branch").path("name").asText(""),
                pr.path("destination").path("branch").path("name").asText(""),
                DiffRefs.headOnly(pr.path("source").path("commit").path("hash").asText("")),
                author(pr.path("author")),
                pr.path("links").path("html").path("href").asText(""),
                type().providerType()));
    }

    private List<IntegrationEvent> closed(JsonNode payload, CloseReason reason) {
        return List.of(new PullRequestClosed(repo(payload), payload.path("pullrequest").path("id").asLong(), reason));
    }

    private List<IntegrationEvent> comment(JsonNode payload) {
        JsonNode comment = payload.path("comment");
        // The self-loop guard (drop bot-authored events, ADR-013) now runs in the
        // orchestrator against the provider registry — the gateway holds no bot
        // account id. The comment's author rides on the emitted event for that check.
        RepoRef repo = repo(payload);
        long prId = payload.path("pullrequest").path("id").asLong();
        String text = comment.path("content").path("raw").asText("").trim();
        Author author = author(comment.path("user"));

        // "/review ..." -> ManualCommandReceived (CONTRACT §10)
        if (text.startsWith("/")) {
            String[] parts = text.substring(1).split("\\s+", 2);
            String command = parts[0].toLowerCase(Locale.ROOT);
            if (commands.contains(command)) {
                return List.of(new ManualCommandReceived(repo, prId,
                        command, parts.length > 1 ? parts[1] : "", author));
            }
        }

        // Reply threads anchor on the ROOT comment id (SCM-MAPPING §6).
        JsonNode parent = comment.path("parent").path("id");
        String threadRef = parent.isMissingNode() || parent.isNull()
                ? comment.path("id").asText()
                : parent.asText();
        return List.of(new AuthorReplied(repo, prId,
                ReviewIds.reviewId(repo, prId),
                new ThreadRef(threadRef),
                comment.path("id").asText(),
                text,
                author));
    }

    /**
     * Bitbucket workspace/repo slug charset — second layer behind the HMAC
     * (security finding L2). Must start and end alphanumeric, so path-traversal
     * shapes like ".." can never form a URL segment.
     */
    private static final java.util.regex.Pattern SLUG =
            java.util.regex.Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?");

    private RepoRef repo(JsonNode payload) {
        String fullName = payload.path("repository").path("full_name").asText("");
        int slash = fullName.indexOf('/');
        if (slash <= 0) {
            throw new IllegalArgumentException("Webhook payload has no repository.full_name");
        }
        String workspace = fullName.substring(0, slash);
        String slug = fullName.substring(slash + 1);
        if (!SLUG.matcher(workspace).matches() || !SLUG.matcher(slug).matches()) {
            throw new IllegalArgumentException("Webhook repository.full_name has an invalid charset");
        }
        return new RepoRef(workspace, slug);
    }

    private Author author(JsonNode user) {
        return Author.of(
                user.path("account_id").asText(""),
                user.path("nickname").asText(""),
                user.path("display_name").asText(""));
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
