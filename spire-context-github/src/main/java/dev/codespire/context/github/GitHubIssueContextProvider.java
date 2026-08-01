package dev.codespire.context.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.MissingNode;
import dev.codespire.contract.port.ContextProvider;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.review.ContribStatus;
import dev.codespire.contract.scm.RepoRef;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Resolves the PR's referenced GitHub issues and pull requests into {@link ContextItem}s for the
 * review prompt. Built per {@code GatherContext} command from the brokered credential (like the SCM
 * adapters), NOT a long-lived singleton — the credential is workspace-scoped and decrypted only
 * inside the worker.
 *
 * <p>Retrieved text is UNTRUSTED (SECURITY.md) — the prompt builder fences it; this provider only
 * shapes it. One bad reference (404/typo) is skipped, not fatal; an auth failure yields an
 * {@code ERROR} contribution so the aggregator records the miss without aborting the review.
 */
public class GitHubIssueContextProvider implements ContextProvider {

    public static final String SOURCE = "GITHUB_ISSUES";
    private static final String KIND_ISSUE = "ISSUE";
    private static final String KIND_PULL_REQUEST = "PULL_REQUEST";
    /** Guard one oversized issue from dominating the shared context budget. */
    private static final int MAX_BODY_CHARS = 4_000;
    /** Include the last few comments — where the real decisions often live — bounded to limit noise. */
    private static final int MAX_COMMENTS = 5;
    private static final int MAX_COMMENT_CHARS = 500;
    /** Page size for the comments call; the issue's own comment count says which page holds the newest. */
    private static final int COMMENTS_PER_PAGE = 100;
    /** Cost guard: a description listing dozens of issues must not become dozens of API calls. */
    private static final int MAX_REFERENCES = 10;

    private final GitHubIssueClient client;
    private final Set<String> repoAllowList;

    public GitHubIssueContextProvider(GitHubIssueConfig config, ObjectMapper mapper) {
        this.client = new GitHubIssueClient(config, mapper);
        this.repoAllowList = config.repoAllowList();
    }

    @Override
    public String source() {
        return SOURCE;
    }

    @Override
    public boolean supports(ContextRequest request) {
        return !resolvable(request).isEmpty();
    }

    @Override
    public CompletionStage<ContextContribution> contribute(ContextRequest request) {
        return CompletableFuture.supplyAsync(() -> fetch(request));
    }

    /**
     * The request's candidates narrowed to what this provider can actually resolve. The request
     * carries every source's candidates, so recognising our own is part of the job.
     *
     * <p>Order is preserved and the result capped, so a link farm cannot drive an unbounded fan-out.
     */
    private List<Target> resolvable(ContextRequest request) {
        Set<String> references = request.references();
        if (references == null || references.isEmpty()) {
            return List.of();
        }
        List<Target> targets = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String reference : references) {
            if (targets.size() >= MAX_REFERENCES) {
                break;
            }
            resolve(reference, request).filter(t -> seen.add(t.key())).ifPresent(targets::add);
        }
        return targets;
    }

    /**
     * A reference plus the repository it belongs to, or empty when this provider cannot say.
     *
     * <p>A repo-relative reference borrows the review's own repository, which is only the right
     * repository when the review runs on THIS platform — the same {@code workspace/slug} exists on
     * other hosts, so resolving it elsewhere would fetch a real but unrelated issue. A qualified
     * reference or a URL names its own repository, so it needs no such guard: an author who writes
     * {@code acme/widgets#12} meant that repository wherever they wrote it.
     */
    private Optional<Target> resolve(String reference, ContextRequest request) {
        return GitHubIssueRefs.parse(reference).flatMap(ref -> {
            if (!ref.isRepoRelative()) {
                return GitHubIssueRefs.allows(repoAllowList, ref.owner(), ref.repo())
                        ? Optional.of(Target.of(ref.owner(), ref.repo(), ref.number()))
                        : Optional.empty();
            }
            RepoRef repo = request.repo();
            if (request.scmType() != ScmType.GITHUB || repo == null) {
                return Optional.empty();
            }
            return GitHubIssueRefs.allows(repoAllowList, repo.workspace(), repo.slug())
                    ? Optional.of(Target.of(repo.workspace(), repo.slug(), ref.number()))
                    : Optional.empty();
        });
    }

    private record Target(String owner, String repo, int number) {

        /**
         * Owner and repository are normalized to lower case here, at the single point every
         * {@link Target} is built, because GitHub treats them case-insensitively. Without this, a
         * bare {@code #12} (borrowing the review's own, already-lower-case repo) and a differently
         * cased qualified {@code Acme/Widgets#12} would still dedupe under {@link #key()} but leave
         * the *surviving* instance's casing to whichever reference happened to resolve first — an
         * arbitrary, iteration-order-dependent fetch target instead of one canonical repository.
         */
        static Target of(String owner, String repo, int number) {
            return new Target(owner.toLowerCase(Locale.ROOT), repo.toLowerCase(Locale.ROOT), number);
        }

        /** De-dup key: one issue referenced two ways (case or form) must cost one fetch, one item. */
        String key() {
            return owner + "/" + repo + "#" + number;
        }

        String path() {
            return "/repos/" + owner + "/" + repo + "/issues/" + number;
        }
    }

    private ContextContribution fetch(ContextRequest request) {
        long start = System.nanoTime();
        List<ContextItem> items = new ArrayList<>();
        try {
            for (Target target : resolvable(request)) {
                resolveItem(target, request).ifPresent(items::add);
            }
        } catch (GitHubIssueApiException e) {
            // Auth/config failure applies to every reference — record ERROR, don't abort the review.
            return new ContextContribution(SOURCE, ContribStatus.ERROR, List.of(), latencyMs(start));
        }
        ContribStatus status = items.isEmpty() ? ContribStatus.EMPTY : ContribStatus.OK;
        return new ContextContribution(SOURCE, status, items, latencyMs(start));
    }

    /** @return the issue or pull request as a ContextItem, or empty when it does not resolve (404). */
    private Optional<ContextItem> resolveItem(Target target, ContextRequest request) {
        JsonNode issue;
        try {
            issue = client.getJson(target.path());
        } catch (GitHubIssueApiException e) {
            if (e.status() == 404) {
                return Optional.empty(); // typo'd or unreachable reference — skip, keep the rest
            }
            throw e; // auth/5xx bubbles up to mark the whole contribution
        }
        // GitHub returns pull requests from the issues endpoint; this key is what distinguishes them.
        boolean isPullRequest = !issue.path("pull_request").isMissingNode();
        String state = issue.path("state").asText("");
        String body = clip(issue.path("body").asText(""), MAX_BODY_CHARS);

        StringBuilder rendered = new StringBuilder();
        rendered.append("State: ").append(state.isBlank() ? "?" : state);
        String labels = labelsOf(issue);
        if (!labels.isBlank()) {
            rendered.append(" | Labels: ").append(labels);
        }
        rendered.append('\n');
        if (!body.isBlank()) {
            rendered.append('\n').append(body);
        }
        appendRecentComments(rendered, target, issue.path("comments").asInt(-1));

        String title = renderTitle(target, request, issue.path("title").asText(""));
        String uri = issue.path("html_url").asText("");
        return Optional.of(new ContextItem(isPullRequest ? KIND_PULL_REQUEST : KIND_ISSUE,
                title, rendered.toString().strip(), uri.isBlank() ? null : uri));
    }

    /**
     * Renders the reference form a title leads with. A bare {@code #123} is only unambiguous while it
     * is read inside the repository it names — if this item's title is later re-mined for references
     * (level 2 of the two-level fetch, {@code ContextWorker}), a bare {@code #123} would resolve
     * against the review's own repository, which is wrong whenever the target repository differs.
     * Rendering the qualified form here means re-extraction sees {@code acme/other#123}, which
     * normalizes into the already-seen set instead of being mistaken for a fresh, repo-relative
     * reference.
     */
    private static String renderTitle(Target target, ContextRequest request, String rawTitle) {
        String suffix = rawTitle.isBlank() ? "" : " — " + rawTitle;
        return (isOwnRepo(target, request) ? "#" + target.number()
                : target.owner() + "/" + target.repo() + "#" + target.number()) + suffix;
    }

    /** Case-insensitive: {@link Target#of} already lower-cases, {@link RepoRef} does not. */
    private static boolean isOwnRepo(Target target, ContextRequest request) {
        RepoRef repo = request.repo();
        if (repo == null) {
            return false;
        }
        return target.owner().equals(repo.workspace().toLowerCase(Locale.ROOT))
                && target.repo().equals(repo.slug().toLowerCase(Locale.ROOT));
    }

    private static String labelsOf(JsonNode issue) {
        JsonNode labels = issue.path("labels");
        if (!labels.isArray() || labels.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (JsonNode label : labels) {
            String name = label.path("name").asText("");
            if (!name.isBlank()) {
                names.add(name);
            }
        }
        return String.join(", ", names);
    }

    /**
     * Append the most recent comments with author and a truncated body, bounded so a chatty issue
     * cannot crowd out the diff.
     *
     * <p>Comments are a second call, so they can fail while the issue itself read fine. They are
     * enrichment: a failure here drops them and keeps the issue rather than losing both.
     */
    private void appendRecentComments(StringBuilder body, Target target, int commentCount) {
        StringBuilder rendered = new StringBuilder();
        for (JsonNode comment : recentComments(target, commentCount)) {
            String text = clip(comment.path("body").asText(""), MAX_COMMENT_CHARS);
            if (text.isBlank()) {
                continue;
            }
            String author = comment.path("user").path("login").asText("");
            rendered.append("\n- ").append(author.isBlank() ? "" : author + ": ").append(text);
        }
        if (rendered.length() > 0) {
            body.append("\n\nRecent comments:").append(rendered);
        }
    }

    /**
     * The newest {@link #MAX_COMMENTS} comments, oldest-first.
     *
     * <p>The endpoint orders comments by ascending id and takes no sort parameter, so on an issue
     * past one page the tail of page 1 is mid-history — while the decisions and the links worth
     * following are at the end. The issue payload carries the total, which is what lets us ask for
     * the last page directly; a last page shorter than the bound is topped up from the one before it.
     *
     * @param commentCount the issue's own count, or -1 when the payload did not carry one.
     */
    private List<JsonNode> recentComments(Target target, int commentCount) {
        if (commentCount == 0) {
            return List.of(); // the issue says it has none — don't spend a call finding out
        }
        int lastPage = commentCount > 0 ? (commentCount - 1) / COMMENTS_PER_PAGE + 1 : 1;
        List<JsonNode> recent = new ArrayList<>(tailOf(commentsPage(target, lastPage)));
        if (recent.size() < MAX_COMMENTS && lastPage > 1) {
            List<JsonNode> previous = tailOf(commentsPage(target, lastPage - 1));
            int missing = Math.min(MAX_COMMENTS - recent.size(), previous.size());
            recent.addAll(0, previous.subList(previous.size() - missing, previous.size()));
        }
        return recent;
    }

    /**
     * One page of comments, or a missing node when the call fails. Comments are a second call, so they
     * can fail while the issue itself read fine: they are enrichment, and a failure here drops them
     * and keeps the issue rather than losing both.
     */
    private JsonNode commentsPage(Target target, int page) {
        try {
            return client.getJson(target.path() + "/comments?per_page=" + COMMENTS_PER_PAGE
                    + "&page=" + page);
        } catch (RuntimeException e) {
            return MissingNode.getInstance();
        }
    }

    /** The last {@link #MAX_COMMENTS} entries of a page, oldest-first. */
    private static List<JsonNode> tailOf(JsonNode page) {
        if (!page.isArray() || page.isEmpty()) {
            return List.of();
        }
        List<JsonNode> tail = new ArrayList<>();
        for (int i = Math.max(0, page.size() - MAX_COMMENTS); i < page.size(); i++) {
            tail.add(page.get(i));
        }
        return tail;
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        return text.length() <= max ? text : text.substring(0, max) + "…";
    }

    private static long latencyMs(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
