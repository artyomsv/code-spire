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

    public PushRefusedException(List<String> refusals) {
        super("the forge refused the push: " + String.join("; ", refusals));
        this.refusals = List.copyOf(refusals);
    }

    /** One entry per refused ref update, each naming the ref, the status and the forge's message. */
    public List<String> refusals() {
        return refusals;
    }
}
