package dev.codespire.scm.gitlab;

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

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    // How GitLab writes an @-mention in a note body (see mentions()).
    private static final Pattern MENTION =
            Pattern.compile("@([A-Za-z0-9](?:[A-Za-z0-9_.-]*[A-Za-z0-9_])?)");

    private static final String MERGE_REQUEST = "merge_request";
    private static final String NOTE = "note";
    private static final String MR_NOTEABLE = "MergeRequest";

    private final String webhookSecret;
    private final ObjectMapper mapper;
    private final Set<String> commands;
    private final boolean reviewDrafts;

    public GitLabIngress(String webhookSecret, ObjectMapper mapper, Set<String> commands) {
        this(webhookSecret, mapper, commands, false);
    }

    /**
     * @param reviewDrafts draft-PR policy (config {@code spire.review.draft-prs}): {@code false}
     *                     (default) skips draft open/update and instead reviews on the draft→ready
     *                     flip (GitLab has no {@code ready_for_review} event, so the flip is detected
     *                     from {@code changes}); {@code true} restores reviewing drafts immediately.
     */
    public GitLabIngress(String webhookSecret, ObjectMapper mapper, Set<String> commands, boolean reviewDrafts) {
        this.webhookSecret = webhookSecret;
        this.mapper = mapper;
        this.commands = Set.copyOf(commands);
        this.reviewDrafts = reviewDrafts;
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

    /**
     * Draft-PR policy (config {@code spire.review.draft-prs}): with {@link #reviewDrafts}
     * false (default), a draft/WIP MR never triggers a review on open/reopen/update — it
     * fires instead when the author flips the MR to ready (detected from {@code changes},
     * since GitLab has no {@code ready_for_review} event). With {@code reviewDrafts} true,
     * drafts are reviewed immediately. Closing/merging always cancels regardless of draft
     * state.
     */
    private List<IntegrationEvent> mergeRequest(JsonNode payload) {
        JsonNode attrs = payload.path("object_attributes");
        boolean skipDraft = isDraft(attrs) && !reviewDrafts;
        return switch (attrs.path("action").asText("")) {
            case "open", "reopen" -> skipDraft ? List.of() : prEvent(payload, attrs, PrAction.OPENED);
            case "update" -> updateEvent(payload, attrs, skipDraft);
            case "close" -> List.of(new PullRequestClosed(repo(payload), iid(attrs), CloseReason.DECLINED));
            case "merge" -> List.of(new PullRequestClosed(repo(payload), iid(attrs), CloseReason.MERGED));
            default -> List.of(); // approved / unapproved / ...
        };
    }

    /** A draft→ready flip reviews even without a new push (GitLab has no ready_for_review event); otherwise
     *  only a push (flagged by oldrev) moves the diff. */
    private List<IntegrationEvent> updateEvent(JsonNode payload, JsonNode attrs, boolean skipDraft) {
        if (becameReady(payload) && !reviewDrafts) {
            return prEvent(payload, attrs, PrAction.OPENED);
        }
        if (skipDraft) {
            return List.of();
        }
        // A bare "update" also fires on label/description edits; only a push (which
        // GitLab flags by including oldrev) moves the diff and warrants a re-review.
        return attrs.has("oldrev") ? prEvent(payload, attrs, PrAction.UPDATED) : List.of();
    }

    private static boolean isDraft(JsonNode attrs) {
        if (attrs.path("work_in_progress").asBoolean(false) || attrs.path("draft").asBoolean(false)) {
            return true;
        }
        String title = attrs.path("title").asText("");
        return title.startsWith("Draft:") || title.startsWith("WIP:");
    }

    /** GitLab signals an un-draft on an update via changes.draft (or changes.work_in_progress) flipping to false. */
    private static boolean becameReady(JsonNode payload) {
        JsonNode changes = payload.path("changes");
        for (String key : new String[]{"draft", "work_in_progress"}) {
            JsonNode change = changes.path(key);
            if (change.path("previous").asBoolean(false) && !change.path("current").asBoolean(true)) {
                return true;
            }
        }
        return false;
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
                (attrs.path("last_commit").path("id").asText("")),
                author(payload.path("user")),
                attrs.path("url").asText(""),
                type().providerType()));
    }

    /**
     * A merge-request comment. A "/command" note becomes ManualCommandReceived when
     * registered (the saga maps "review" -> force review), dropped when unregistered.
     * Any other note becomes {@code AuthorReplied}: a threaded reply (GitLab {@code type}
     * of {@code DiffNote}/{@code DiscussionNote}) is keyed to its {@code discussion_id}
     * ({@code topLevel = false}); an individual top-level note ({@code type} absent/null)
     * is {@code topLevel = true}. Notes on issues/commits/snippets are ignored via
     * {@code noteable_type}. The MR number is {@code merge_request.iid}, not the note's
     * own id.
     */
    /**
     * A DiffNote's {@code position}. GitLab reports {@code new_line} for an added/context line and
     * only {@code old_line} for a removed one, so fall back rather than losing the location; a
     * DiscussionNote or plain note has no position and yields null.
     */
    private static ThreadLocation location(JsonNode attrs) {
        JsonNode position = attrs.path("position");
        if (position.isMissingNode() || position.isNull()) {
            return null;
        }
        String path = position.path("new_path").asText(position.path("old_path").asText(null));
        return ThreadLocation.of(path, firstInt(position, "new_line", "old_line"));
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

    private List<IntegrationEvent> note(JsonNode payload) {
        JsonNode attrs = payload.path("object_attributes");
        if (!MR_NOTEABLE.equals(attrs.path("noteable_type").asText(""))) {
            return List.of();
        }
        String text = attrs.path("note").asText("").trim();
        long iid = payload.path("merge_request").path("iid").asLong();
        RepoRef repo = repo(payload);
        if (!text.startsWith("/")) {
            String noteType = attrs.path("type").asText(null);       // DiffNote/DiscussionNote => threaded; null => top-level
            boolean topLevel = noteType == null || noteType.isBlank();
            String discussionId = attrs.path("discussion_id").asText("");
            String noteId = attrs.path("id").asText("");
            return List.of(new AuthorReplied(repo, iid, ReviewIds.reviewId(repo, iid),
                    new ThreadRef(discussionId), noteId, text, author(payload.path("user")), topLevel,
                    mentions(text), location(attrs)));
        }
        String[] parts = text.substring(1).split("\\s+", 2);
        String command = parts[0].toLowerCase(Locale.ROOT);
        if (!commands.contains(command)) {
            return List.of();
        }
        return List.of(new ManualCommandReceived(repo, iid, command,
                parts.length > 1 ? parts[1] : "", author(payload.path("user"))));
    }

    /**
     * GitLab renders a mention as {@code @username} in the note body. Usernames may contain dots,
     * dashes and underscores but not two dots in a row, and cannot end in punctuation.
     *
     * <p>This lives in the ingress because only it sees GitLab's rendering — the core is handed the
     * identities that were mentioned and never learns any provider's syntax.
     */
    private static List<String> mentions(String text) {
        if (text == null || text.indexOf('@') < 0) {
            return List.of();
        }
        List<String> found = new ArrayList<>();
        Matcher m = MENTION.matcher(text);
        while (m.find()) {
            found.add(m.group(1));
        }
        return found;
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
