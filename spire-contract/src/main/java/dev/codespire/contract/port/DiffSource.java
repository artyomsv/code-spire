package dev.codespire.contract.port;

import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;

/** SCM read adapter. Diffs are fetched on demand and never persisted (ADR-011). */
public interface DiffSource {

    ScmType type();

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
}
