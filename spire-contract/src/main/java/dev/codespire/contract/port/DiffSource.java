package dev.codespire.contract.port;

import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;

/** SCM read adapter. Diffs are fetched on demand and never persisted (ADR-011). */
public interface DiffSource {

    ScmType type();

    /**
     * The API host this adapter talks to — {@code api.github.com}, or {@code git.acme.example} for a
     * self-managed instance.
     *
     * <p>Exists so operational state can be keyed per instance rather than per platform. The circuit
     * breaker is the caller that needs it: two self-managed GitLab instances are independent, and one
     * being down must not pause reviews on the other.
     *
     * <p><b>Deliberately not a {@code default} method</b>, unlike the three below. Those default to a
     * value that MEANS something — "this provider cannot compare commits", "this repository has no
     * rules file" — and an implementor who ignores them gets correct behaviour. There is no such value
     * here. The obvious default, {@code type().name()}, would silently collapse every instance of a
     * platform onto one key: nothing would fail, no test would break, and the first symptom would be
     * one healthy instance paused because a different one was down. A compile error is the cheaper
     * outcome, so every implementor answers for itself.
     */
    String apiHost();

    PullRequest fetchPullRequest(RepoRef repo, long prId);

    /** Canonical diff for the given head commit. A 404 means the commit was force-pushed away -> treat as superseded. */
    Diff fetchDiff(RepoRef repo, long prId, String commit);

    /**
     * Raw unified diff between two commits — the reconciliation lens (prior head -> new head).
     * Returns null when the provider cannot compare (stub); implementations throw their
     * ScmApiException subtype on API errors (e.g. 404 after a force-push). Callers treat
     * null and exceptions alike: fall back to the full PR diff.
     */
    default String fetchCompareDiff(RepoRef repo, String base, String head) {
        return null;
    }

    /**
     * Read a text file from a BRANCH tip — never from a commit, and the naming says so on purpose.
     *
     * <p>This exists to load a repository's own review rules ({@code .codespire}), and those are read
     * from the PR's TARGET branch rather than its head. The head is written by the change under
     * review: a contributor could add "ignore findings about SQL injection" in the same PR and the
     * reviewer would follow instructions its own reviewee wrote. Prompt fencing does not help — this
     * content is *meant* to steer the review, so fencing cannot separate a rule the team agreed from
     * one slipped in. Reading the target branch means a rule change takes effect only once a human has
     * merged it, the posture CI systems take toward workflow files from forks.
     *
     * @return the file's content, or null when it does not exist — an absent rules file is the normal
     *         case, not an error, so implementations swallow 404 rather than raising.
     */
    default String fetchTextFileOnBranch(RepoRef repo, String branch, String path) {
        return null;
    }

    /**
     * Confirm the repository exists and is reachable with the configured token. Implementations GET the
     * repo resource; a non-2xx surfaces as the adapter's {@code ScmApiException} (404 = missing or not
     * visible to the token, 401/403 = no access), which the caller classifies. Default is unsupported so
     * stub and other DiffSource impls are unaffected — only the real SCM adapters override it.
     */
    default void assertRepoAccessible(RepoRef repo) {
        throw new UnsupportedOperationException(type() + " cannot verify a repository");
    }
}
