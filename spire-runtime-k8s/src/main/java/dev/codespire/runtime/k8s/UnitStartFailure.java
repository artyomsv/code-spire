package dev.codespire.runtime.k8s;

import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStateTerminated;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodCondition;
import io.fabric8.kubernetes.api.model.PodStatus;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Why a unit's pod did not get its agent started, as the run's failure detail says it: the pod's phase, each
 * container's state with its exit code and OOMKilled flag, then the newest lines of the failed container's own
 * output, bounded. The same shape as the Docker arm's sidecar start failure, so both read alike on the run row.
 */
final class UnitStartFailure {

    /** At most this many of the newest lines. */
    static final int TAIL_LINES = 20;

    /** And at most this many characters of them, joined. */
    static final int TAIL_CHARS = 2048;

    static final String SEPARATOR = " | ";

    /** The waiting reasons that mean a container cannot get its image. */
    static final Set<String> IMAGE_REASONS = Set.of("ErrImagePull", "ImagePullBackOff", "InvalidImageName");

    private static final String OOM_KILLED = "OOMKilled";

    private static final String CUT = "...";

    private static final String UNKNOWN = "unknown";

    private UnitStartFailure() {
    }

    /** {@code headline (phase P; c1 state; c2 state); last output: a | b}. */
    static String detail(String headline, Pod pod, List<String> logTail) {
        List<String> tail = tail(logTail);
        return headline + " (" + describe(pod) + "); last output: "
                + (tail.isEmpty() ? "none" : String.join(SEPARATOR, tail));
    }

    static boolean isImageProblem(Pod pod) {
        for (ContainerStatus status : statuses(pod)) {
            ContainerState state = status.getState();
            if (state != null && state.getWaiting() != null
                    && IMAGE_REASONS.contains(state.getWaiting().getReason())) {
                return true;
            }
        }
        return false;
    }

    /** Init containers first, in their order, then the others. */
    static List<ContainerStatus> statuses(Pod pod) {
        List<ContainerStatus> statuses = new ArrayList<>();
        PodStatus status = pod == null ? null : pod.getStatus();
        if (status == null) {
            return statuses;
        }
        if (status.getInitContainerStatuses() != null) {
            statuses.addAll(status.getInitContainerStatuses());
        }
        if (status.getContainerStatuses() != null) {
            statuses.addAll(status.getContainerStatuses());
        }
        return statuses;
    }

    /**
     * The newest lines of {@code log}: at most {@link #TAIL_LINES}, joined at most {@link #TAIL_CHARS} characters. A
     * newest line longer than that alone keeps its end, which is what names a failure.
     */
    static List<String> tail(List<String> log) {
        List<String> kept = new ArrayList<>();
        int used = 0;
        for (int index = log.size() - 1; index >= 0 && kept.size() < TAIL_LINES; index--) {
            String line = log.get(index);
            int cost = line.length() + (kept.isEmpty() ? 0 : SEPARATOR.length());
            if (used + cost > TAIL_CHARS) {
                if (kept.isEmpty()) {
                    kept.add(CUT + line.substring(line.length() - (TAIL_CHARS - CUT.length())));
                }
                break;
            }
            kept.add(line);
            used += cost;
        }
        Collections.reverse(kept);
        return kept;
    }

    private static String describe(Pod pod) {
        PodStatus status = pod == null ? null : pod.getStatus();
        StringBuilder text = new StringBuilder("phase ")
                .append(status == null || status.getPhase() == null ? UNKNOWN : status.getPhase());
        if (status != null && status.getReason() != null) {
            text.append(", reason ").append(status.getReason());
        }
        appendUnscheduled(status, text);
        List<ContainerStatus> statuses = statuses(pod);
        if (statuses.isEmpty()) {
            text.append("; no container status yet");
        }
        for (ContainerStatus container : statuses) {
            text.append("; ").append(container.getName()).append(' ').append(stateOf(container));
        }
        return text.toString();
    }

    private static void appendUnscheduled(PodStatus status, StringBuilder text) {
        if (status == null || status.getConditions() == null) {
            return;
        }
        for (PodCondition condition : status.getConditions()) {
            if ("PodScheduled".equals(condition.getType()) && "False".equals(condition.getStatus())) {
                text.append("; not scheduled: ").append(orUnknown(condition.getReason()))
                        .append(' ').append(orUnknown(condition.getMessage()));
            }
        }
    }

    private static String stateOf(ContainerStatus container) {
        ContainerState state = container.getState();
        if (state == null) {
            return UNKNOWN;
        }
        if (state.getRunning() != null) {
            return "running";
        }
        if (state.getWaiting() != null) {
            return "waiting " + orUnknown(state.getWaiting().getReason())
                    + " (image " + orUnknown(container.getImage()) + ")";
        }
        ContainerStateTerminated ended = state.getTerminated();
        if (ended == null) {
            return UNKNOWN;
        }
        return "terminated, exit " + orUnknown(ended.getExitCode())
                + ", OOMKilled " + OOM_KILLED.equals(ended.getReason());
    }

    private static String orUnknown(Object value) {
        return value == null || value.toString().isBlank() ? UNKNOWN : value.toString();
    }
}
