package dev.codespire.workspace;

import java.util.List;

/**
 * The forge refused the push.
 *
 * <p>Exists because JGit does not raise one. {@code PushCommand.call()} returns per-ref
 * {@code RemoteRefUpdate.Status} values and throws nothing, so a non-fast-forward, an
 * authentication failure and a pre-receive hook refusal all look identical to success unless the
 * result is read. Reported as a refusal rather than a warning because two guarantees depend on it:
 * the forge-side ruleset RUN-TOPOLOGY §6.3 recommends as the second layer of defence, and §5's
 * claim that a crashed run loses only minutes — which holds only while every checkpoint that
 * reported success actually reached the remote.
 */
public class PushRefusedException extends RuntimeException {

    private final List<String> refusals;

    private final boolean nonFastForward;

    public PushRefusedException(List<String> refusals) {
        this(refusals, false);
    }

    /** The forge refused for reasons of its own: a ruleset, a hook, or nothing attempted. */
    public static PushRefusedException refused(List<String> refusals) {
        return new PushRefusedException(refusals, false);
    }

    /** The remote's branch moved under this run, so the push is not a fast-forward. */
    public static PushRefusedException branchMoved(List<String> refusals) {
        return new PushRefusedException(refusals, true);
    }

    private PushRefusedException(List<String> refusals, boolean nonFastForward) {
        super("the forge refused the push: " + String.join("; ", refusals));
        this.refusals = List.copyOf(refusals);
        this.nonFastForward = nonFastForward;
    }

    /**
     * Whether the remote's branch moved under this run, rather than the forge refusing for its own
     * reasons.
     *
     * <p>A resumed run, a human commit on the run's branch, or two replicas of one run all produce
     * it. Reported as a plain push failure it points an operator at the forge and is retried, which
     * pushes the same stale parent again; told apart it names the divergence and stops. Never
     * resolved by force-pushing from the publisher — the fix is to clone the branch rather than the
     * base commit, which is what the resume work will do.
     */
    public boolean isNonFastForward() {
        return nonFastForward;
    }

    /** One entry per refused ref update, each naming the ref, the status and the forge's message. */
    public List<String> refusals() {
        return refusals;
    }
}
