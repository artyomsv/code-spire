package dev.codespire.runtime.k8s;

import dev.codespire.runtime.Finalization;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.time.Duration;

/**
 * Waits for a unit pod to end and reads how it ended. The worker calls salvage as soon as the agent runs, so the
 * wait is the pod's own deadline ({@code activeDeadlineSeconds}: init timeout plus wall clock, set by this arm) plus
 * the drain window for its grace. A failed read is retried until that deadline: one blip of the API server must not
 * end a paid review that is still running. Deletes nothing.
 */
final class UnitSalvage {

    static final Duration POLL = Duration.ofSeconds(2);

    private final KubernetesClient client;

    private final KubernetesRuntimeConfig config;

    private final Duration drainWindow;

    UnitSalvage(KubernetesClient client, KubernetesRuntimeConfig config, Duration drainWindow) {
        this.client = client;
        this.config = config;
        this.drainWindow = drainWindow;
    }

    /** The pod's deadline plus the drain window; before the pod was read, the init timeout stands in for it. */
    Duration waitFor(Pod pod) {
        Long deadline = pod == null || pod.getSpec() == null ? null : pod.getSpec().getActiveDeadlineSeconds();
        Duration podDeadline = deadline == null ? config.initTimeout() : Duration.ofSeconds(deadline);
        return podDeadline.plus(drainWindow);
    }

    Finalization salvage(String runId) {
        String name = UnitNames.unitPod(runId);
        long started = System.nanoTime();
        Duration limit = waitFor(null);
        KubernetesClientException lastError = null;
        while (true) {
            try {
                Pod pod = client.pods().inNamespace(config.namespace()).withName(name).get();
                if (pod == null) {
                    return Finalization.vanished("no unit pod of run " + runId + " remains");
                }
                if (UnitOutcome.hasEnded(pod)) {
                    return UnitOutcome.of(pod, proxyPodOf(runId));
                }
                limit = waitFor(pod);
                lastError = null;
            } catch (KubernetesClientException unreadable) {
                lastError = unreadable;
            }
            if (System.nanoTime() - started > limit.toNanos()) {
                return lastError != null
                        ? Finalization.faulted("the unit pod " + name + " could not be read until " + limit
                                + " had passed: " + lastError.getMessage())
                        : Finalization.faulted("the unit pod " + name + " did not end within " + limit
                                + "; it is left running for inspection");
            }
            if (!pause()) {
                return Finalization.faulted("interrupted while waiting for the unit pod " + name
                        + " to end; it is left running for inspection");
            }
        }
    }

    /** Best effort: the proxy pod only adds a note to the outcome. */
    private Pod proxyPodOf(String runId) {
        try {
            return client.pods().inNamespace(config.namespace()).withName(UnitNames.proxyPod(runId)).get();
        } catch (KubernetesClientException unreadable) {
            return null;
        }
    }

    /** @return false when interrupted, with the flag set again */
    private static boolean pause() {
        try {
            Thread.sleep(POLL);
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
