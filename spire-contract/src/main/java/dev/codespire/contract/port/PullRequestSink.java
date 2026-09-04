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
 * adapter. See SCM-MAPPING.md §8.
 *
 * <p><b>The credential is the adapter's, resolved the way every other port resolves it</b> — the
 * caller names a repository, not a token. What differs for this port is WHICH account: a pull
 * request is opened by the FACTORY-role machine account, never the reviewer's. An earlier version of
 * this javadoc justified that by saying the reviewer's author allowlist would otherwise skip the
 * pull request it had opened itself. <b>That was wrong</b>, and a review caught it by reading the
 * saga rather than this sentence: nothing guards pull-request authorship — the bot-authored check
 * covers comments and commands only — and an empty allowlist means everyone, so by default the
 * reviewer WOULD review its own. The real reasons are narrower and still sufficient: the branch is
 * pushed as the factory account, so a pull request opened as the reviewer misattributes the work;
 * the reviewer's token is not provisioned for that write, and its 403 reads as the factory account
 * failing; and an operator who HAS set an allowlist gets the skip after all.
 */
public interface PullRequestSink {

    ScmType type();

    /**
     * Open a pull request, or answer the one that is already open from this head onto this base.
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
     * The OPEN pull request from {@code headBranch} onto {@code baseBranch}, or empty when there is
     * none.
     *
     * <p><b>Both branches, and the base is not decoration.</b> An open pull request is unique per
     * (head, base) PAIR on every forge — GitHub's own duplicate refusal fires only when both match,
     * and all three permit {@code spire/x → main} and {@code spire/x → develop} open at once. A
     * lookup keyed on the head alone is therefore WIDER than the rule the forge enforces: it can
     * answer a pull request aimed somewhere else, which the caller then records as this run's
     * delivery while the pull request that should exist never gets opened. ADR-040's existing-branch
     * mode makes that reachable by design, since it pushes onto a branch that already has one.
     *
     * <p><b>Open, not any.</b> A merged or closed pull request must not suppress a new one — branch
     * names are reused, and a run whose work nobody can review is the failure this port exists to
     * prevent.
     *
     * <p><b>A read fault must THROW, never answer empty.</b> Empty is the answer that authorises
     * opening one, so an adapter that cannot reach its forge would open a duplicate every time the
     * record is redelivered. {@code FixRuns} and {@code FactoryRunProjection.fixRunFor} take the same
     * posture for the same reason: unknown is not absent.
     */
    Optional<PullRequestRef> findByHead(RepoRef repo, String headBranch, String baseBranch);

    /**
     * The head branch has no commits the base does not already have, so there is nothing to open.
     *
     * <p><b>An outcome, not a fault.</b> It is what a run whose agent changed nothing looks like
     * from here, and every forge reports it as a 4xx that reads like an error — GitHub as a 422
     * saying "No commits between", which an operator will read as a permission or a plumbing
     * problem and go looking in the wrong place entirely.
     *
     * <p>Named by the PORT rather than by each adapter, because normalising exactly this kind of
     * per-forge spelling is what the port is for. An adapter recognises its own forge's status AND
     * wording and throws this; a caller distinguishes "the agent produced nothing" from "the machine
     * account cannot open pull requests here" without knowing which forge it is talking to.
     *
     * <p><b>Unchecked, so a caller must decide deliberately what to do with it.</b> The safe shape is
     * to record the run as finished-with-nothing; the dangerous one is a blanket
     * {@code RuntimeException} retry, which would spend a GET, a POST and a 4xx against the forge on
     * every attempt, with the factory's write credential, until the ack budget runs out. Said here
     * because the consumer does not exist yet and this is the sentence it should meet first.
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
     * @param baseBranch what it was branched from, and what the pull request targets. Part of the
     *     identity of a pull request, not merely of its content — see {@link #findByHead}
     * @param title one line, shown in the forge's list
     * @param bodyMd the description. <b>May contain agent-influenced text</b>, which the caller
     *     bounds and fences; see {@code FactoryPullRequestBody}. It is read by humans AND by the
     *     reviewer's own model on the next round, so it is untrusted output as much as untrusted
     *     input
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
