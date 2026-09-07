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
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadLocation;
import dev.codespire.contract.scm.ThreadRef;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // How Bitbucket Cloud writes an @-mention in raw comment text (see mentions()).
    private static final Pattern BRACED_MENTION = Pattern.compile("@\\{([^}\\s]{1,128})}");
    private static final Pattern PLAIN_MENTION =
            Pattern.compile("@([A-Za-z0-9](?:[A-Za-z0-9_-]*[A-Za-z0-9])?)");

    private final ObjectMapper mapper;
    private final BitbucketCloudConfig config;
    private final Set<String> commands;
    private final boolean reviewDrafts;

    public BitbucketCloudIngress(BitbucketCloudConfig config, ObjectMapper mapper, Set<String> commands) {
        this(config, mapper, commands, false);
    }

    public BitbucketCloudIngress(BitbucketCloudConfig config, ObjectMapper mapper,
                                 Set<String> commands, boolean reviewDrafts) {
        this.config = config;
        this.mapper = mapper;
        this.commands = Set.copyOf(commands);
        this.reviewDrafts = reviewDrafts;
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
            case "pullrequest:created" -> maybeReview(payload, PrAction.OPENED);
            case "pullrequest:updated" -> maybeReview(payload, PrAction.UPDATED);
            case "pullrequest:fulfilled" -> closed(payload, CloseReason.MERGED);
            case "pullrequest:rejected" -> closed(payload, CloseReason.DECLINED);
            case "pullrequest:comment_created" -> comment(payload);
            default -> List.of();
        };
    }

    /** A draft PR is skipped unless reviewDrafts; an un-draft arrives as a non-draft pullrequest:updated. */
    private List<IntegrationEvent> maybeReview(JsonNode payload, PrAction action) {
        boolean draft = payload.path("pullrequest").path("draft").asBoolean(false);
        if (draft && !reviewDrafts) {
            return List.of();
        }
        return pullRequestEvent(payload, action);
    }

    /**
     * Whether the pull request comes from a forked repository.
     *
     * <p>Bitbucket nests the repository under {@code source}/{@code destination} rather than
     * {@code head}/{@code base}, so the field names differ from GitHub's while the comparison is
     * the same. A blank on either side reads as not-a-fork.
     */
    private static boolean fromFork(JsonNode pr) {
        String source = pr.path("source").path("repository").path("full_name").asText("");
        String destination = pr.path("destination").path("repository").path("full_name").asText("");
        return !source.isBlank() && !destination.isBlank() && !source.equals(destination);
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
                (pr.path("source").path("commit").path("hash").asText("")),
                author(pr.path("author")),
                pr.path("links").path("html").path("href").asText(""),
                type().providerType(),
                fromFork(pr)));
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

        // Reply threads anchor on the ROOT comment id (SCM-MAPPING §6).
        JsonNode parent = comment.path("parent").path("id");
        boolean hasParent = !(parent.isMissingNode() || parent.isNull());
        boolean inline = comment.path("inline").isObject();
        // A plain top-level PR comment (no parent, not inline) is answered in the summary thread (topLevel).
        boolean topLevel = !hasParent && !inline;
        String threadRef = hasParent ? parent.asText() : comment.path("id").asText();
        ThreadLocation location = location(comment);

        // "/review ..." -> ManualCommandReceived (CONTRACT §10), now carrying the thread it was
        // typed in. A top-level command has no thread of its own.
        if (text.startsWith("/")) {
            String[] parts = text.substring(1).split("\\s+", 2);
            String command = parts[0].toLowerCase(Locale.ROOT);
            if (commands.contains(command)) {
                return List.of(new ManualCommandReceived(repo, prId, command,
                        parts.length > 1 ? parts[1] : "", author,
                        topLevel ? null : new ThreadRef(threadRef), location,
                        comment.path("id").asText()));
            }
        }

        return List.of(new AuthorReplied(repo, prId, ReviewIds.reviewId(repo, prId),
                new ThreadRef(threadRef), comment.path("id").asText(), text, author, topLevel,
                mentions(text), location));
    }

    /**
     * Bitbucket workspace/repo slug charset — second layer behind the HMAC
     * (security finding L2). Must start and end alphanumeric, so path-traversal
     * shapes like ".." can never form a URL segment.
     */
    private static final java.util.regex.Pattern SLUG =
            java.util.regex.Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._-]*[A-Za-z0-9])?");

    /**
     * Bitbucket Cloud writes a mention into the comment's RAW text as {@code @{account_id}} — the
     * login never appears — so the braced account id is the form that matters. A plain
     * {@code @nickname} is also collected, for renderings that carry one.
     *
     * <p>This lives in the ingress because only it sees Bitbucket's rendering. The core is handed
     * the identities that were mentioned and compares them to the bot's, without knowing that this
     * provider is the one that brackets its ids.
     */
    /**
     * Bitbucket's {@code inline} block. {@code to} is the NEW-side line and {@code from} the OLD side;
     * a comment on a removed line carries only {@code from}, so fall back rather than losing the
     * location. A plain PR comment has no {@code inline} block and yields null.
     */
    private static ThreadLocation location(JsonNode comment) {
        JsonNode inline = comment.path("inline");
        if (!inline.isObject()) {
            return null;
        }
        return ThreadLocation.of(inline.path("path").asText(null), firstInt(inline, "to", "from"));
    }

    /**
     * The first of {@code fields} present as an integer, else null. A loop rather than nested
     * ternaries: mixing {@code int} and {@code Integer} branches unboxes the null one and throws.
     */
    private static Integer firstInt(JsonNode node, String... fields) {
        for (String field : fields) {
            if (node.path(field).isIntegralNumber()) {
                return node.path(field).asInt();
            }
        }
        return null;
    }

    private static List<String> mentions(String text) {
        if (text == null || text.indexOf('@') < 0) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        Matcher braced = BRACED_MENTION.matcher(text);
        while (braced.find()) {
            found.add(braced.group(1));
        }
        Matcher plain = PLAIN_MENTION.matcher(text);
        while (plain.find()) {
            found.add(plain.group(1));
        }
        return found;
    }

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
