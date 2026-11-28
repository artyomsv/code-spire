package dev.codespire.runtime.k8s;

import dev.codespire.runtime.Finalization;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;

/**
 * How an ended unit pod maps to a {@link Finalization}, as on the Docker arm: the exit code is the agent's.
 *
 * <ul>
 *   <li>The pod passed its deadline: overran.</li>
 *   <li>The agent exited non-zero: salvaged with its code, whether or not it wrote DONE. A publisher stopped before
 *       DONE is said in the detail, and so is a cause such as {@code OOMKilled}.</li>
 *   <li>The agent exited 0 but never wrote DONE (the publisher exited 75): faulted, since nothing was handed off.</li>
 *   <li>The agent exited 0 otherwise: salvaged 0.</li>
 *   <li>No exit of the agent was seen: faulted.</li>
 * </ul>
 */
final class UnitOutcome {

    /** {@code spire-publish-none --stay-until-term}: TERM arrived before the agent wrote DONE. */
    static final int PUBLISHER_TERM_BEFORE_DONE = 75;

    static final String DEADLINE_EXCEEDED = "DeadlineExceeded";

    static final String PUBLISHER_STOPPED_BEFORE_DONE = "the publisher was stopped before the agent wrote DONE";

    static final String NO_DONE_NOTE = "; the publisher was stopped before DONE";

    /** The kubelet's reason for any non-zero exit; it says nothing the code does not. */
    private static final String GENERIC_REASON = "Error";

    private static final String PHASE_SUCCEEDED = "Succeeded";

    private static final String PHASE_FAILED = "Failed";

    private UnitOutcome() {
    }

    static boolean hasEnded(Pod pod) {
        String phase = phaseOf(pod);
        return PHASE_SUCCEEDED.equals(phase) || PHASE_FAILED.equals(phase);
    }

    /** @param proxyPod the unit's proxy pod, or null when it is gone */
    static Finalization of(Pod unitPod, Pod proxyPod) {
        String note = proxyNote(proxyPod);
        if (DEADLINE_EXCEEDED.equals(unitPod.getStatus().getReason())) {
            return Finalization.overran("the unit pod passed its deadline of "
                    + unitPod.getSpec().getActiveDeadlineSeconds() + " s and was stopped" + note);
        }
        ContainerStateTerminated publisher = terminatedOf(unitPod, UnitPod.PUBLISHER);
        boolean noDone = publisher != null
                && Integer.valueOf(PUBLISHER_TERM_BEFORE_DONE).equals(publisher.getExitCode());
        ContainerStateTerminated agent = terminatedOf(unitPod, UnitPod.AGENT);
        if (agent == null || agent.getExitCode() == null) {
            return Finalization.faulted("the unit pod ended in phase " + phaseOf(unitPod)
                    + " with no exit of its agent" + (noDone ? NO_DONE_NOTE : "") + note);
        }
        int code = agent.getExitCode();
        if (code == 0 && noDone) {
            return Finalization.faulted(PUBLISHER_STOPPED_BEFORE_DONE + "; agent exited 0" + note);
        }
        return Finalization.salvaged(code, "agent exited " + code + reasonOf(agent) + (noDone ? NO_DONE_NOTE : "")
                + note);
    }

    private static String reasonOf(ContainerStateTerminated agent) {
        String reason = agent.getReason();
        return reason == null || reason.isBlank() || GENERIC_REASON.equals(reason) ? "" : " (" + reason + ")";
    }

    /**
     * A proxy pod evicted or failed while the agent ran shows up in the agent as a model error. Naming it here sends
     * an operator to the node, not to the vendor.
     */
    private static String proxyNote(Pod proxyPod) {
        if (proxyPod == null || !PHASE_FAILED.equals(phaseOf(proxyPod))) {
            return "";
        }
        String reason = proxyPod.getStatus().getReason();
        return "; the proxy pod " + proxyPod.getMetadata().getName() + " failed ("
                + (reason == null ? "no reason given" : reason) + ")";
    }

    private static ContainerStateTerminated terminatedOf(Pod pod, String container) {
        for (ContainerStatus status : UnitStartFailure.statuses(pod)) {
            if (container.equals(status.getName())) {
                return status.getState() == null ? null : status.getState().getTerminated();
            }
        }
        return null;
    }

    private static String phaseOf(Pod pod) {
        return pod.getStatus() == null ? null : pod.getStatus().getPhase();
    }
}
