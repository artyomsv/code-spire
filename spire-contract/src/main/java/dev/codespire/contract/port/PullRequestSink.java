package dev.codespire.contract.port;

import dev.codespire.contract.scm.PullRequestRef;
import dev.codespire.contract.scm.RepoRef;

import java.util.Objects;
import java.util.Optional;

/**
 * SCM write adapter that OPENS a pull request — the port M2 needs and this codebase has never had.
 *
 * <p>{@link ScmIngress}, {@link DiffSource} and {@link CommentSink} are the existing three, and none
 * of them can create one: the reviewer only ever comments on pull requests other people opened. A
 * factory run pushes a branch, and a branch nobody reviews is not a delivery.
 *
 * <p>Every reference here is opaque in the same sense the other ports mean it. The caller supplies a
 * head branch and a base branch by name and gets back a {@link PullRequestRef}; how a forge spells
 * "head", whether it needs a namespace prefix, and what its API calls the resource stay inside the
 * adapter. See SCM-MAPPING.md.
 *
 * <p><b>The credential is the adapter's, resolved the way every other port resolves it</b> — the
 * caller names a repository, not a token. What differs for this port is WHICH account: a pull
 * request is opened by the FACTORY-role machine account, never the reviewer's, because a branch
 * pushed as one identity and a pull request opened as another is a pull request nobody can attribute.
 */
public interface PullRequestSink {

    ScmType type();

    /**
     * Open a pull request, or answer the one that is already open for this head branch.
     *
     * <p><b>Idempotent by contract, not by hope.</b> {@code RunFinished} is a Kafka record and is
     * redelivered on every consumer restart; by then the push has happened, so the branch exists and
     * the API would cheerfully create a second pull request from the same head. GitHub happens to
     * refuse that with a 422 — GitLab and Bitbucket do not — so "let the forge decide" is not a rule,
     * it is one forge's behaviour that two others do not share.
     *
     * <p>An implementation therefore checks {@link #findByHead} first and returns what it finds. The
     * second call is not an error: the caller needs the number either way, to record it.
     */
    PullRequestRef open(RepoRef repo, NewPullRequest request);

    /**
     * The OPEN pull request whose source branch is {@code headBranch}, or empty when there is none.
     *
     * <p><b>Open, not any.</b> A merged or closed pull request for a branch name must not suppress a
     * new one — branch names are reused, and a run that produced work nobody can review is the
     * failure this whole port exists to prevent.
     *
     * <p><b>A read fault must THROW, never answer empty.</b> Empty is the answer that authorises
     * opening one, so an adapter that cannot reach its forge would open a duplicate every time the
     * record is redelivered. {@code FixRuns} and {@code FactoryRunProjection.fixRunFor} take the same
     * posture for the same reason: unknown is not absent.
     */
    Optional<PullRequestRef> findByHead(RepoRef repo, String headBranch);

    /**
     * The head branch has no commits the base does not already have, so there is nothing to open.
     *
     * <p><b>An outcome, not a fault.</b> It is what a run whose agent changed nothing looks like
     * from here, and every forge reports it as a 4xx that reads like an error — GitHub as a 422
     * saying "No commits between", which an operator will read as a permission or a plumbing
     * problem and go looking in the wrong place entirely.
     *
     * <p>Named by the PORT rather than by each adapter, because normalising exactly this kind of
     * per-forge spelling is what the port is for. An adapter recognises its own forge's wording and
     * throws this; a caller distinguishes "the agent produced nothing" from "the machine account
     * cannot open pull requests here" without knowing which forge it is talking to.
     */
    class NothingToPropose extends RuntimeException {

        public NothingToPropose(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /**
     * What to open.
     *
     * @param headBranch the branch the run pushed — the source
     * @param baseBranch what it was branched from, and what the pull request targets
     * @param title one line, shown in the forge's list
     * @param bodyMd the description. <b>May contain agent-authored text</b>, which the caller fences;
     *     see {@code FactoryPullRequestBody}. It is read by humans AND by the reviewer's own model on
     *     the next round, so it is untrusted output as much as untrusted input
     */
    record NewPullRequest(String headBranch, String baseBranch, String title, String bodyMd) {

        public NewPullRequest {
            Objects.requireNonNull(headBranch, "headBranch");
            Objects.requireNonNull(baseBranch, "baseBranch");
            Objects.requireNonNull(title, "title");
            Objects.requireNonNull(bodyMd, "bodyMd");
            if (headBranch.isBlank() || baseBranch.isBlank()) {
                throw new IllegalArgumentException("a pull request needs both branches by name: head='"
                        + headBranch + "', base='" + baseBranch + "'");
            }
            if (headBranch.equals(baseBranch)) {
                // Every forge refuses this, and each with its own opaque message. Refusing here means
                // the caller learns it is a caller bug rather than reading a 422 about "no commits".
                throw new IllegalArgumentException(
                        "a pull request cannot be opened from a branch onto itself: " + headBranch);
            }
            if (title.isBlank()) {
                throw new IllegalArgumentException("a pull request needs a title");
            }
        }
    }
}
