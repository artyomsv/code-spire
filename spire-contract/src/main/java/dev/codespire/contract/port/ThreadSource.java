package dev.codespire.contract.port;

import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.ThreadTranscript;

/**
 * Read a review thread back from the SCM on demand (spec §5, §7) — the conversation is never stored
 * (ADR-011). {@code ThreadRef} is opaque: a comment id on GitHub/Bitbucket, a discussion_id on GitLab.
 */
public interface ThreadSource {

    ScmType type();

    ThreadTranscript fetchThread(RepoRef repo, long prId, ThreadRef thread);
}
