package dev.codespire.scm.gitlab;

import com.fasterxml.jackson.databind.JsonNode;
import dev.codespire.contract.port.PullRequestSink;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.PullRequestRef;
import dev.codespire.contract.scm.RepoRef;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Opens a merge request on GitLab (SCM-MAPPING.md §8).
 *
 * <p>Two of §8's divergences live here, and both are silent when wrong.
 *
 * <p><b>GitLab numbers a merge request twice.</b> {@code iid} is the per-project number in the URL
 * and in every API path; {@code id} is a global identifier that addresses nothing a human can see.
 * Reading {@code id} yields a number that looks entirely valid, stores cleanly in
 * {@code factory_run.pr_id}, and points at some other project's merge request. There is no failure —
 * only a link that goes somewhere else.
 *
 * <p><b>GitLab does not refuse a duplicate.</b> Unlike GitHub, a second create from the same source
 * branch succeeds and opens a second merge request. So the find-first call is not belt-and-braces
 * here; it is the only thing standing between a redelivered record and two merge requests.
 */
public class GitLabPullRequestSink implements PullRequestSink {

    /**
     * <b>Unverified, and the least certain constant in this module.</b>
     *
     * <p>SCM-MAPPING §8's GitLab row lists {@code 409 "branch conflicts"} and an empty-diff 400 for
     * this case; neither contains this phrase, and the test that covers it stubs a body this
     * repository invented. Two reviewers independently flagged that the adapter and the table
     * disagree, and one raised the stronger possibility that GitLab CREATES a merge request with no
     * commit difference rather than refusing — in which case this arm is unreachable rather than
     * merely mismatched.
     *
     * <p>Kept, gated on the status, and recorded in {@code docs/UNVERIFIED.md} as a specific
     * uncertainty rather than the general one. The failure direction decides it: if the phrase never
     * matches, a no-diff run reports the forge's own error, which is honest; if it matches wrongly, a
     * real failure is reported as "the agent changed nothing", which is not. The status gate makes
     * the second direction much harder without making the first any likelier. This wants one
     * measurement against a live GitLab (SMOKE-TEST Mode G) before anything depends on it.
     */
    private static final String NO_CHANGES = "no changes";

    /**
     * The status the wording must ALSO carry, and here it is load-bearing rather than tidy.
     *
     * <p>{@code "no changes"} is two generic words matched against a 500-character raw body snippet.
     * On its own it can match a 500, a proxy error page, or a message that merely quotes a branch
     * name — and a false match degrades in the one direction the port exists to prevent. An unmatched
     * failure degrades safely, so the asymmetry decides: pin the wording to the status it comes with.
     */
    private static final int CONFLICT = 409;

    private final GitLabClient client;

    public GitLabPullRequestSink(GitLabClient client) {
        this.client = client;
    }

    @Override
    public ScmType type() {
        return ScmType.GITLAB;
    }

    @Override
    public PullRequestRef open(RepoRef repo, NewPullRequest request) {
        return findByHead(repo, request.headBranch(), request.baseBranch())
                .orElseGet(() -> create(repo, request));
    }

    private PullRequestRef create(RepoRef repo, NewPullRequest request) {
        String path = mergeRequestsPath(repo);
        try {
            return read(client.postJson(path, Map.of(
                    "source_branch", request.headBranch(),
                    "target_branch", request.baseBranch(),
                    "title", request.title(),
                    "description", request.bodyMd())), "POST", path);
        } catch (GitLabApiException e) {
            return recover(repo, request, e);
        }
    }

    @Override
    public Optional<PullRequestRef> findByHead(RepoRef repo, String headBranch, String baseBranch) {
        requireBranch(headBranch, "source");
        requireBranch(baseBranch, "target");
        // BOTH branches: a merge request is unique per (source, target) pair, and GitLab will happily
        // hold spire/x -> main and spire/x -> develop open at once. Filtering on the source alone
        // would answer whichever came first and record it as this run's delivery.
        //
        // "opened", not "open" -- GitLab spells this state differently from every other forge, and a
        // wrong value is not an error here: the API ignores an unknown state filter and returns
        // everything, so a merged merge request would suppress a new one and nothing would say why.
        String path = mergeRequestsPath(repo) + "?state=opened&per_page=1"
                + "&target_branch=" + encode(baseBranch)
                + "&source_branch=" + encode(headBranch);
        JsonNode found = client.getJson(path);
        if (!found.isArray() || found.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(read(found.get(0), "GET", path));
    }

    /**
     * What a refused create really meant. See the GitHub adapter for the full argument.
     *
     * <p>Only the nothing-to-propose wording is matched; any other refusal asks the forge whether a
     * merge request exists now, because a race between the lookup and the create is identifiable by
     * behaviour rather than by a wording no forge promises to keep.
     */
    private PullRequestRef recover(RepoRef repo, NewPullRequest request, GitLabApiException e) {
        String detail = e.getMessage() == null ? "" : e.getMessage().toLowerCase(Locale.ROOT);
        if (e.status() == CONFLICT && detail.contains(NO_CHANGES)) {
            throw new NothingToPropose("the branch " + request.headBranch() + " has no changes "
                    + request.baseBranch() + " does not already have, so there is nothing to review", e);
        }
        try {
            return findByHead(repo, request.headBranch(), request.baseBranch()).orElseThrow(() -> e);
        } catch (GitLabApiException lookupFailed) {
            // Suppressed, not thrown: a fault on the re-read must not replace the refusal that
            // started this, or the operator reads a failed GET and never learns the create was denied.
            if (lookupFailed != e) {
                e.addSuppressed(lookupFailed);
            }
            throw e;
        }
    }

    /**
     * <b>{@code iid}, never {@code id}.</b> See the class javadoc: the wrong one is a valid-looking
     * number pointing at another project.
     */
    private static PullRequestRef read(JsonNode node, String method, String path) {
        long number = node.path("iid").asLong(0);
        String url = node.path("web_url").asText("");
        if (number <= 0 || url.isBlank()) {
            throw new GitLabApiException(200, method, path,
                    "response carried no merge request iid or web_url");
        }
        return new PullRequestRef(number, url);
    }

    private static void requireBranch(String branch, String which) {
        if (branch == null || branch.isBlank()) {
            throw new IllegalArgumentException(
                    "a " + which + " branch is needed to look for its merge request");
        }
    }

    /** GitLab addresses a project by URL-encoded {@code namespace/path}, nesting included. */
    private static String mergeRequestsPath(RepoRef repo) {
        return "/projects/" + encode(repo.full()) + "/merge_requests";
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
