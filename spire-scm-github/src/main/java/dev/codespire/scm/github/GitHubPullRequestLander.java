package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.port.PullRequestLander;
import dev.codespire.contract.scm.RepoRef;

import java.io.UncheckedIOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Lands a pull request on GitHub. Every read goes through {@link GitHubClient#getIdentityJson}, so
 * no redirected foreign origin can supply the state a merge or a branch delete is decided on.
 *
 * <ul>
 *   <li>{@link #read}: {@code GET /pulls/{n}}, then the head's {@code statuses[]} and every page of
 *   its check runs, reduced by {@link GitHubCheckReduction} (A4). Annotations are read for failed
 *   check runs only.</li>
 *   <li>{@link #merge}: {@code PUT /pulls/{n}/merge} with {@code sha} and {@code merge_method}. A 4xx
 *   is definite; a 5xx and an I/O failure, an unreadable response included, are uncertain (A5).</li>
 *   <li>{@link #deleteBranch}: reads the ref and sends the {@code DELETE} only when it read the
 *   expected sha; after any answer but 204 the ref is read again, and only its absence counts as gone
 *   (A13). GitHub's ref {@code DELETE} takes no expected sha, so this is check-then-act: a push that
 *   lands between the read and the {@code DELETE} is deleted with the branch (UNVERIFIED §E).</li>
 * </ul>
 */
public final class GitHubPullRequestLander implements PullRequestLander {
    /** Mirrors the approval source: a full last page at the cap cannot prove the history complete. */
    static final int CHECK_RUN_PAGES = 10;
    private static final int PAGE_SIZE = 100;
    private static final Pattern SHA = Pattern.compile("[0-9a-f]{40}");
    private static final ObjectMapper MESSAGES = new ObjectMapper();

    private final GitHubClient client;

    public GitHubPullRequestLander(GitHubClient client) {
        this.client = client;
    }

    @Override
    public boolean available() {
        return true;
    }

    @Override
    public LandState read(RepoRef repo, long pullRequest) {
        requirePullRequest(pullRequest);
        JsonNode pull = client.getIdentityJson(repoPath(repo) + "/pulls/" + pullRequest);
        if (pull.path("number").asLong() != pullRequest) {
            throw new IllegalStateException("GitHub answered pull request " + pull.path("number").asText()
                    + " for " + repo.full() + "#" + pullRequest);
        }
        boolean merged = pull.path("merged").asBoolean(false);
        String headSha = pull.path("head").path("sha").asText("");
        JsonNode mergeable = pull.path("mergeable");
        return new LandState(pull.path("state").asText(""), merged,
                merged ? text(pull.path("merge_commit_sha")) : null,
                merged ? text(pull.path("merged_by").path("id")) : null,
                headSha, pull.path("head").path("ref").asText(""), pull.path("base").path("ref").asText(""),
                pull.path("base").path("sha").asText(""),
                mergeable.isBoolean() ? Boolean.valueOf(mergeable.asBoolean()) : null,
                text(pull.path("mergeable_state")), checks(repo, headSha));
    }

    @Override
    public AllowedMethods allowedMethods(RepoRef repo) {
        JsonNode repository = client.getIdentityJson(repoPath(repo));
        Map<String, MergeMethod> flags = Map.of("allow_merge_commit", MergeMethod.MERGE,
                "allow_squash_merge", MergeMethod.SQUASH, "allow_rebase_merge", MergeMethod.REBASE);
        Set<MergeMethod> allowed = EnumSet.noneOf(MergeMethod.class);
        for (Map.Entry<String, MergeMethod> flag : flags.entrySet()) {
            JsonNode value = repository.path(flag.getKey());
            // The flags are only reported to an account with push access; an absent one proves nothing.
            if (!value.isBoolean()) return new AllowedMethods(false, Set.of());
            if (value.asBoolean()) allowed.add(flag.getValue());
        }
        return new AllowedMethods(true, allowed);
    }

    @Override
    public MergeOutcome merge(RepoRef repo, MergeRequest request) {
        requirePullRequest(request.pullRequest());
        requireSha(request.expectedHead());
        String path = repoPath(repo) + "/pulls/" + request.pullRequest() + "/merge";
        try {
            JsonNode response = client.putJson(path,
                    Map.of("sha", request.expectedHead(), "merge_method", request.method().name().toLowerCase(Locale.ROOT)));
            String sha = response.path("sha").asText("");
            if (!SHA.matcher(sha).matches()) return new MergeOutcome.Uncertain("merge answer carried no merge sha");
            return new MergeOutcome.Merged(sha);
        } catch (GitHubApiException e) {
            if (e.status() == 409) return new MergeOutcome.HeadConflict(message(e));
            // A gateway answer is a lost answer: GitHub can finish the merge after its edge gives up.
            if (e.status() >= 500) return new MergeOutcome.Uncertain("HTTP " + e.status());
            return new MergeOutcome.Refused(e.status(), message(e));
        } catch (UncheckedIOException e) {
            return new MergeOutcome.Uncertain(e.getMessage());
        }
    }

    @Override
    public BranchDeletion deleteBranch(RepoRef repo, String branch, String expectedSha) {
        requireSha(expectedSha);
        String encoded = encodeBranch(branch);
        String found = refSha(repo, encoded);
        if (found == null) return BranchDeletion.ABSENT;
        if (!expectedSha.equals(found)) return BranchDeletion.MOVED;
        if (deleteStatus(repoPath(repo) + "/git/refs/heads/" + encoded) == 204) return BranchDeletion.DELETED;
        // 404 and 422 answer both "already gone" and "protected", and a lost answer says nothing:
        // only a re-read of the ref can tell them apart.
        String after = refSha(repo, encoded);
        if (after == null) return BranchDeletion.ABSENT;
        return expectedSha.equals(after) ? BranchDeletion.STILL_PRESENT : BranchDeletion.MOVED;
    }

    /** The DELETE's status; an HTTP failure is its status and an I/O failure is 0, both re-read by the caller. */
    private int deleteStatus(String path) {
        try {
            return client.delete(path);
        } catch (GitHubApiException e) {
            return e.status();
        } catch (UncheckedIOException e) {
            return 0;
        }
    }

    private CheckSummary checks(RepoRef repo, String headSha) {
        if (!SHA.matcher(headSha).matches()) throw new IllegalStateException("Pull request head carried no sha");
        String commit = repoPath(repo) + "/commits/" + headSha;
        JsonNode combined = client.getIdentityJson(commit + "/status?per_page=" + PAGE_SIZE);
        JsonNode statuses = combined.path("statuses");
        if (!statuses.isArray()) throw new IllegalStateException("Commit statuses could not be read for " + headSha);
        boolean complete = combined.path("total_count").asInt(0) <= statuses.size();
        List<JsonNode> runs = new ArrayList<>();
        boolean pagesComplete = false;
        for (int page = 1; page <= CHECK_RUN_PAGES; page++) {
            JsonNode rows = client.getIdentityJson(commit + "/check-runs?per_page=" + PAGE_SIZE + "&page=" + page)
                    .path("check_runs");
            if (!rows.isArray()) throw new IllegalStateException("Check runs could not be read for " + headSha);
            rows.forEach(runs::add);
            if (rows.size() < PAGE_SIZE) {
                pagesComplete = true;
                break;
            }
        }
        CheckSummary reduced = GitHubCheckReduction.reduce(statuses, runs, run -> annotations(repo, run));
        return complete && pagesComplete ? reduced : incomplete(reduced);
    }

    /** History that could not be read in full is never PASSED, nor FAILED: no fix can complete it. */
    private static CheckSummary incomplete(CheckSummary reduced) {
        List<FailedCheck> failed = new ArrayList<>();
        failed.add(new FailedCheck("check history", "history_incomplete", "Check history incomplete",
                "More checks are reported than one read returns; the checks were not all read.", List.of()));
        for (FailedCheck check : reduced.failed()) {
            if (failed.size() == GitHubCheckReduction.MAX_FAILED) break;
            failed.add(check);
        }
        return new CheckSummary(Checks.NOT_CODE, reduced.done(), reduced.total(), failed);
    }

    private List<String> annotations(RepoRef repo, JsonNode run) {
        JsonNode rows = client.getIdentityJson(repoPath(repo) + "/check-runs/" + run.path("id").asLong()
                + "/annotations?per_page=" + GitHubCheckReduction.MAX_ANNOTATIONS);
        List<String> lines = new ArrayList<>();
        if (!rows.isArray()) return lines;
        for (JsonNode row : rows) {
            lines.add(row.path("path").asText("") + ":" + row.path("start_line").asText("") + ": "
                    + row.path("message").asText(""));
        }
        return lines;
    }

    /** The ref's sha, or null when GitHub says it does not exist. */
    private String refSha(RepoRef repo, String encodedBranch) {
        try {
            JsonNode ref = client.getIdentityJson(repoPath(repo) + "/git/ref/heads/" + encodedBranch);
            String sha = ref.path("object").path("sha").asText("");
            if (!SHA.matcher(sha).matches()) throw new IllegalStateException("Branch ref carried no sha");
            return sha;
        } catch (GitHubApiException e) {
            if (e.status() == 404) return null;
            throw e;
        }
    }

    private static String repoPath(RepoRef repo) {
        return "/repos/" + repo.full();
    }

    private static String encodeBranch(String branch) {
        if (branch == null || branch.isBlank() || branch.startsWith("/") || branch.endsWith("/") || branch.contains("..")) {
            throw new IllegalArgumentException("A branch name is required");
        }
        List<String> segments = new ArrayList<>();
        for (String segment : branch.split("/")) {
            segments.add(URLEncoder.encode(segment, StandardCharsets.UTF_8).replace("+", "%20"));
        }
        return String.join("/", segments);
    }

    private static void requirePullRequest(long pullRequest) {
        if (pullRequest < 1) throw new IllegalArgumentException("A pull request number is required");
    }

    private static void requireSha(String sha) {
        if (sha == null || !SHA.matcher(sha).matches()) throw new IllegalArgumentException("A full commit sha is required");
    }

    private static String text(JsonNode node) {
        return node.isNull() || node.isMissingNode() ? null : node.asText();
    }

    /** GitHub's own message from the error body; the whole detail when the body is not JSON. */
    private static String message(GitHubApiException e) {
        String detail = e.detail();
        if (detail == null || detail.isBlank()) return e.getMessage();
        try {
            String message = MESSAGES.readTree(detail).path("message").asText("");
            return message.isBlank() ? detail : message;
        } catch (com.fasterxml.jackson.core.JsonProcessingException notJson) {
            return detail;
        }
    }
}
