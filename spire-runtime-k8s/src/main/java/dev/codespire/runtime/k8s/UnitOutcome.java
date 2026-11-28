package dev.codespire.runtime.k8s;

import dev.codespire.runtime.Finalization;
import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;

/**
 * How an ended unit pod maps to a {@link Finalization}. The exit code is the agent's, as on the Docker arm; the pod's
 * deadline and a publisher stopped before {@code DONE} come first, because both end the agent from outside.
 */
final class UnitOutcome {

    /** {@code spire-publish-none --stay-until-term}: TERM arrived before the agent wrote DONE. */
    static final int PUBLISHER_TERM_BEFORE_DONE = 75;

    static final String DEADLINE_EXCEEDED = "DeadlineExceeded";

    static final String PUBLISHER_STOPPED_BEFORE_DONE = "the publisher was stopped before the agent wrote DONE";

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
        Integer publisher = exitOf(unitPod, UnitPod.PUBLISHER);
        if (publisher != null && publisher == PUBLISHER_TERM_BEFORE_DONE) {
            return Finalization.faulted(PUBLISHER_STOPPED_BEFORE_DONE + note);
        }
        Integer agent = exitOf(unitPod, UnitPod.AGENT);
        if (agent != null) {
            return Finalization.salvaged(agent, "agent exited " + agent + note);
        }
        return Finalization.faulted("the unit pod ended in phase " + phaseOf(unitPod)
                + " with no exit of its agent" + note);
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

    private static Integer exitOf(Pod pod, String container) {
        for (ContainerStatus status : UnitStartFailure.statuses(pod)) {
            if (!container.equals(status.getName())) {
                continue;
            }
            ContainerState state = status.getState();
            return state == null || state.getTerminated() == null ? null : state.getTerminated().getExitCode();
        }
        return null;
    }

    private static String phaseOf(Pod pod) {
        return pod.getStatus() == null ? null : pod.getStatus().getPhase();
    }
}
