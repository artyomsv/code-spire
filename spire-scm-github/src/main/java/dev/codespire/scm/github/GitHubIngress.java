package dev.codespire.scm.github;

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
import java.util.regex.Pattern;

/**
 * GitHub webhook ingress (SCM-MAPPING §7): {@code X-Hub-Signature-256}
 * HMAC-SHA256 verification (constant-time compare) and translation of the
 * {@code pull_request} / {@code issue_comment} events into integration events.
 * The self-loop guard (dropping bot-authored events, ADR-013) runs downstream in
 * the orchestrator, which holds the registry's resolved bot account id; each
 * event carries its author (the numeric GitHub id as {@code providerUserId}, so
 * the guard can match it).
 *
 * <p>Takes the per-repo webhook secret directly, not a {@link GitHubConfig}: the
 * ingress needs no API token or base URL — the internet-facing gateway holds
 * neither.
 */
public class GitHubIngress implements ScmIngress {

    private static final String HMAC_SHA256 = "HmacSHA256";

    // owner/repo slug charset — second layer behind the HMAC: must start and end
    // alphanumeric, so a path-traversal shape like ".." can never form a segment.
    private static final Pattern SLUG = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?");

    private final String webhookSecret;
    private final ObjectMapper mapper;
    private final Set<String> commands;

    public GitHubIngress(String webhookSecret, ObjectMapper mapper, Set<String> commands) {
        this.webhookSecret = webhookSecret;
        this.mapper = mapper;
        this.commands = Set.copyOf(commands);
    }

    @Override
    public ScmType type() {
        return ScmType.GITHUB;
    }

    @Override
    public boolean verifySignature(RawWebhook raw) {
        String header = header(raw, "x-hub-signature-256");
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
            mac.init(new SecretKeySpec(webhookSecret.getBytes(StandardCharsets.UTF_8), HMAC_SHA256));
            byte[] actual = mac.doFinal(raw.body());
            return MessageDigest.isEqual(expected, actual); // constant-time
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    @Override
    public List<IntegrationEvent> translate(RawWebhook raw) {
        String event = header(raw, "x-github-event");
        if (event == null) {
            return List.of();
        }
        JsonNode payload = parse(raw.body());
        return switch (event) {
            case "pull_request" -> pullRequest(payload);
            case "issue_comment" -> issueComment(payload);
            default -> List.of(); // ping and everything else are no-ops
        };
    }

    private List<IntegrationEvent> pullRequest(JsonNode payload) {
        String action = payload.path("action").asText("");
        return switch (action) {
            case "opened", "reopened" -> prEvent(payload, PrAction.OPENED);
            case "synchronize" -> prEvent(payload, PrAction.UPDATED);
            case "closed" -> List.of(new PullRequestClosed(repo(payload), prNumber(payload),
                    payload.path("pull_request").path("merged").asBoolean(false)
                            ? CloseReason.MERGED : CloseReason.DECLINED));
            default -> List.of();
        };
    }

    private List<IntegrationEvent> prEvent(JsonNode payload, PrAction action) {
        JsonNode pr = payload.path("pull_request");
        return List.of(new PullRequestEventReceived(
                repo(payload),
                prNumber(payload),
                action,
                pr.path("title").asText(""),
                pr.path("body").asText(""),
                pr.path("head").path("ref").asText(""),
                pr.path("base").path("ref").asText(""),
                DiffRefs.headOnly(pr.path("head").path("sha").asText("")),
                author(pr.path("user")),
                pr.path("html_url").asText(""),
                type().providerType()));
    }

    /**
     * A top-level PR comment. Only "/command" comments matter here — a matching
     * command becomes ManualCommandReceived (the saga maps "review" -> force
     * review). issue_comment fires for issues too; the {@code issue.pull_request}
     * node is present only on PRs. Non-command replies are not emitted:
     * conversational follow-ups (AuthorReplied) are a parked roadmap item, so
     * emitting them would be dead output today.
     */
    private List<IntegrationEvent> issueComment(JsonNode payload) {
        if (!"created".equals(payload.path("action").asText(""))
                || payload.path("issue").path("pull_request").isMissingNode()) {
            return List.of();
        }
        String text = payload.path("comment").path("body").asText("").trim();
        if (!text.startsWith("/")) {
            return List.of();
        }
        String[] parts = text.substring(1).split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        if (!commands.contains(command)) {
            return List.of();
        }
        return List.of(new ManualCommandReceived(repo(payload), issueNumber(payload),
                command, parts.length > 1 ? parts[1] : "", author(payload.path("comment").path("user"))));
    }

    private RepoRef repo(JsonNode payload) {
        String fullName = payload.path("repository").path("full_name").asText("");
        int slash = fullName.indexOf('/');
        if (slash <= 0) {
            throw new IllegalArgumentException("Webhook payload has no repository.full_name");
        }
        String owner = fullName.substring(0, slash);
        String repo = fullName.substring(slash + 1);
        if (!SLUG.matcher(owner).matches() || !SLUG.matcher(repo).matches()) {
            throw new IllegalArgumentException("Webhook repository.full_name has an invalid charset");
        }
        return new RepoRef(owner, repo);
    }

    private static long prNumber(JsonNode payload) {
        return payload.path("pull_request").path("number").asLong();
    }

    private static long issueNumber(JsonNode payload) {
        return payload.path("issue").path("number").asLong();
    }

    /** GitHub user object: numeric {@code id} is the stable identity the self-loop guard matches. */
    private Author author(JsonNode user) {
        return Author.of(
                user.path("id").asText(""),
                user.path("login").asText(""),
                user.path("login").asText(""));
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
