package dev.codespire.runtime.k8s;

import io.fabric8.kubernetes.api.model.ContainerState;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.ContainerResource;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/**
 * Waits for a unit's two pods to start, and names why one did not. Every wait is bounded. A failure leaves the
 * unit's objects in place: the anchor keeps it discoverable, and its pods keep the logs that explain it.
 */
final class UnitStart {

    /** {@code spire-seal}: a canary answered, or the control did not, or the rounds never passed. */
    static final int SEAL_NOT_SEALED = 3;

    /** {@code spire-fetch-closed}: the fetch proxy still answered after the close. */
    static final int FETCH_NOT_CLOSED = 4;

    static final String POLICY_NOT_ENFORCED = "network_policy_not_enforced";

    static final String FETCH_PROXY_NOT_CLOSED = "fetch_proxy_not_closed";

    private static final Duration POLL = Duration.ofMillis(500);

    private static final String PHASE_SUCCEEDED = "Succeeded";

    private static final String PHASE_FAILED = "Failed";

    private final KubernetesClient client;

    private final KubernetesRuntimeConfig config;

    UnitStart(KubernetesClient client, KubernetesRuntimeConfig config) {
        this.client = client;
        this.config = config;
    }

    /** Waits until the proxy pod's model proxy and egress proxy passed their startup probes; returns its IP. */
    String awaitProxies(String runId) {
        String name = UnitNames.proxyPod(runId);
        Duration limit = config.unitStartTimeout();
        long deadline = System.nanoTime() + limit.toNanos();
        while (true) {
            Pod pod = present(name);
            failIfSealFailed(pod, ProxyPod.SEAL);
            failIfAnyInitFailed(pod, "an init container of the proxy pod failed");
            if (hasEnded(pod)) {
                throw failure(pod, "the proxy pod ended before its proxies started");
            }
            if (isStarted(pod, ProxyPod.MODEL_PROXY) && isStarted(pod, ProxyPod.EGRESS_PROXY)
                    && pod.getStatus().getPodIP() != null) {
                return pod.getStatus().getPodIP();
            }
            if (System.nanoTime() > deadline) {
                throw failure(pod, "the proxies of " + name + " did not start within " + limit);
            }
            pause(name);
        }
    }

    /** Waits until the unit pod's agent is running (or already finished), mapping each init failure to its cause. */
    void awaitAgent(String runId) {
        String name = UnitNames.unitPod(runId);
        Duration limit = config.unitStartTimeout().plus(config.sealDeadline()).plus(config.initTimeout());
        long deadline = System.nanoTime() + limit.toNanos();
        while (true) {
            Pod pod = present(name);
            // MUTATION: the unit pod seal mapping removed
            failIfFetchFailed(pod, runId);
            failIfFetchProxyOpen(pod);
            // Before the generic check: once the agent ran, a publisher stopped by its end is no start failure.
            if (hasAgentStarted(pod)) {
                return;
            }
            failIfAnyInitFailed(pod, "an init container of the unit pod failed");
            if (hasEnded(pod)) {
                throw failure(pod, "the unit pod ended before its agent started");
            }
            if (System.nanoTime() > deadline) {
                throw failure(pod, "the agent of " + name + " did not start within " + limit);
            }
            pause(name);
        }
    }

    /** A read the API server refused is a runtime that is not available, never a raw client error. */
    private Pod present(String name) {
        Pod pod;
        try {
            pod = client.pods().inNamespace(config.namespace()).withName(name).get();
        } catch (KubernetesClientException unreadable) {
            throw new UnitNotStarted("could not read the pod " + name + " while it started: "
                    + unreadable.getMessage(), false);
        }
        if (pod == null) {
            throw new UnitNotStarted("the pod " + name + " is gone before it started", false);
        }
        return pod;
    }

    private void failIfSealFailed(Pod pod, String seal) {
        Integer exit = exitOf(pod, seal);
        if (exit != null && exit == SEAL_NOT_SEALED) {
            String line = lastReportLine(logOf(pod, seal, false));
            throw new UnitNotStarted(POLICY_NOT_ENFORCED + FetchFailureReport.oneLine(line), false);
        }
    }

    /** The Docker arm's exception and text for a failed clone, so the worker classifies it unchanged. */
    private void failIfFetchFailed(Pod pod, String runId) {
        Integer exit = exitOf(pod, UnitPod.FETCH);
        if (exit != null && exit != 0) {
            throw new IllegalStateException("init container failed with exit " + exit + " for run " + runId
                    + FetchFailureReport.of(logOf(pod, UnitPod.FETCH, false)));
        }
    }

    private void failIfFetchProxyOpen(Pod pod) {
        Integer exit = exitOf(pod, UnitPod.FETCH_CLOSED);
        if (exit != null && exit == FETCH_NOT_CLOSED) {
            String line = lastReportLine(logOf(pod, UnitPod.FETCH_CLOSED, false));
            throw new UnitNotStarted(FETCH_PROXY_NOT_CLOSED + FetchFailureReport.oneLine(line), false);
        }
    }

    private void failIfAnyInitFailed(Pod pod, String headline) {
        List<ContainerStatus> inits = pod.getStatus() == null ? null : pod.getStatus().getInitContainerStatuses();
        if (inits == null) {
            return;
        }
        for (ContainerStatus init : inits) {
            Integer exit = exitOf(init);
            if (exit != null && exit != 0) {
                throw failure(pod, headline);
            }
        }
    }

    /** The detail of a start that failed for no named cause: the pod's state and the failed container's tail. */
    private UnitNotStarted failure(Pod pod, String headline) {
        List<String> tail = firstFailed(pod).map(name -> logOf(pod, name, true)).orElse(List.of());
        return new UnitNotStarted(UnitStartFailure.detail(headline, pod, tail), UnitStartFailure.isImageProblem(pod));
    }

    private static Optional<String> firstFailed(Pod pod) {
        for (ContainerStatus status : UnitStartFailure.statuses(pod)) {
            Integer exit = exitOf(status);
            if (exit != null && exit != 0) {
                return Optional.of(status.getName());
            }
        }
        return Optional.empty();
    }

    /** A container's log, best effort: a log that cannot be read must not replace the real failure. */
    private List<String> logOf(Pod pod, String container, boolean tailOnly) {
        try {
            ContainerResource resource = client.pods().inNamespace(config.namespace())
                    .withName(pod.getMetadata().getName()).inContainer(container);
            String log = tailOnly ? resource.tailingLines(UnitStartFailure.TAIL_LINES).getLog() : resource.getLog();
            return log == null ? List.of() : log.lines().toList();
        } catch (KubernetesClientException unreadable) {
            return List.of();
        }
    }

    /** The seal and fetch-closed print one JSON line before they fail; anything else the JVM said comes before it. */
    private static String lastReportLine(List<String> log) {
        String last = "";
        for (String line : log) {
            if (line.startsWith("{")) {
                last = line;
            } else if (last.isEmpty() && !line.isBlank()) {
                last = line;
            }
        }
        return last;
    }

    private static boolean hasEnded(Pod pod) {
        String phase = pod.getStatus() == null ? null : pod.getStatus().getPhase();
        return PHASE_SUCCEEDED.equals(phase) || PHASE_FAILED.equals(phase);
    }

    private static boolean isStarted(Pod pod, String container) {
        return statusOf(pod, container).map(status -> Boolean.TRUE.equals(status.getStarted())).orElse(false);
    }

    private static boolean hasAgentStarted(Pod pod) {
        Optional<ContainerState> state = statusOf(pod, UnitPod.AGENT).map(ContainerStatus::getState);
        return state.map(s -> s.getRunning() != null || s.getTerminated() != null).orElse(false);
    }

    private static Integer exitOf(Pod pod, String container) {
        return statusOf(pod, container).map(UnitStart::exitOf).orElse(null);
    }

    private static Integer exitOf(ContainerStatus status) {
        ContainerState state = status.getState();
        return state == null || state.getTerminated() == null ? null : state.getTerminated().getExitCode();
    }

    private static Optional<ContainerStatus> statusOf(Pod pod, String container) {
        for (ContainerStatus status : UnitStartFailure.statuses(pod)) {
            if (container.equals(status.getName())) {
                return Optional.of(status);
            }
        }
        return Optional.empty();
    }

    private static void pause(String name) {
        try {
            Thread.sleep(POLL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new UnitNotStarted("interrupted while waiting for the pod " + name + " to start", false);
        }
    }
}
