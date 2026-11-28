package dev.codespire.runtime.k8s;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.DeletionPropagation;
import io.fabric8.kubernetes.api.model.HasMetadata;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Stops and removes a unit's objects. Every delete is by name or by the unit's own label, never by role alone, so a
 * teardown cannot reach another unit. A delete of an object that is already gone is success. Secrets are deleted by
 * name only: the arm may not list them.
 */
final class UnitTeardown {

    static final String CANCELLED_ANNOTATION = "dev.codespire/cancelled";

    static final String CANCELLED = "true";

    /** How long {@code destroy} waits for the unit's last object to leave the listings. */
    static final Duration DESTROY_WAIT = Duration.ofSeconds(120);

    private static final Duration POLL = Duration.ofMillis(500);

    private final KubernetesClient client;

    private final String namespace;

    UnitTeardown(KubernetesClient client, String namespace) {
        this.client = client;
        this.namespace = namespace;
    }

    /** The proxy pod first: it holds the model credential. Each failure is kept on {@code failure}, none stops it. */
    void stopPods(String runId, RuntimeException failure) {
        for (String pod : List.of(UnitNames.proxyPod(runId), UnitNames.unitPod(runId))) {
            try {
                deletePod(pod);
            } catch (KubernetesClientException unstopped) {
                failure.addSuppressed(unstopped);
            }
        }
    }

    void deletePods(String runId) {
        deletePod(UnitNames.proxyPod(runId));
        deletePod(UnitNames.unitPod(runId));
    }

    void deleteSecrets(String runId) {
        for (String secret : UnitNames.secretNames(runId)) {
            deleteSecret(secret);
        }
    }

    void deleteSecret(String name) {
        client.secrets().inNamespace(namespace).withName(name).delete();
    }

    /** Marks the anchor before anything is stopped, so a {@code create} still running sees it. Once is enough. */
    void markCancelled(String runId) {
        String name = UnitNames.anchor(runId);
        ConfigMap anchor = client.configMaps().inNamespace(namespace).withName(name).get();
        if (anchor == null || isMarked(anchor)) {
            return;
        }
        try {
            client.configMaps().inNamespace(namespace).withName(name).edit(current -> new ConfigMapBuilder(current)
                    .editMetadata().addToAnnotations(CANCELLED_ANNOTATION, CANCELLED).endMetadata().build());
        } catch (KubernetesClientException e) {
            if (e.getCode() != HttpURLConnection.HTTP_NOT_FOUND) {
                throw e;
            }
        }
    }

    /** True when the anchor is marked cancelled, or is gone: a destroyed unit must not grow a pod either. */
    boolean isCancelled(String runId) {
        ConfigMap anchor = client.configMaps().inNamespace(namespace).withName(UnitNames.anchor(runId)).get();
        return anchor == null || isMarked(anchor);
    }

    /**
     * Secrets by name, the anchor with foreground propagation, then whatever still carries the unit's label, until
     * nothing is listed.
     */
    void destroy(String runId) {
        deleteSecrets(runId);
        client.configMaps().inNamespace(namespace).withName(UnitNames.anchor(runId))
                .withPropagationPolicy(DeletionPropagation.FOREGROUND).delete();
        String digest = UnitNames.digest(runId);
        long deadline = System.nanoTime() + DESTROY_WAIT.toNanos();
        while (true) {
            List<HasMetadata> left = listed(digest);
            if (left.isEmpty()) {
                return;
            }
            deleteEach(left);
            if (System.nanoTime() > deadline) {
                throw new IllegalStateException("destroy of run " + runId + ": " + names(left) + " still listed in "
                        + namespace + " after " + DESTROY_WAIT + "; look for a finalizer that holds them");
            }
            pause(runId);
        }
    }

    private List<HasMetadata> listed(String digest) {
        List<HasMetadata> left = new ArrayList<>();
        left.addAll(client.pods().inNamespace(namespace).withLabel(UnitNames.ROLE_LABEL).list().getItems());
        left.addAll(client.network().v1().networkPolicies().inNamespace(namespace)
                .withLabel(UnitNames.ROLE_LABEL).list().getItems());
        left.addAll(client.configMaps().inNamespace(namespace)
                .withLabel(UnitNames.ROLE_LABEL).list().getItems());
        return left;
    }

    /** An object already being deleted is left to finish. */
    private void deleteEach(List<HasMetadata> left) {
        for (HasMetadata object : left) {
            if (object.getMetadata().getDeletionTimestamp() != null) {
                continue;
            }
            String name = object.getMetadata().getName();
            if (object instanceof Pod) {
                deletePod(name);
            } else if (object instanceof NetworkPolicy) {
                client.network().v1().networkPolicies().inNamespace(namespace).withName(name).delete();
            } else {
                client.configMaps().inNamespace(namespace).withName(name).delete();
            }
        }
    }

    private void deletePod(String name) {
        client.pods().inNamespace(namespace).withName(name)
                .withGracePeriod(KubernetesRunRuntime.STOP_GRACE.toSeconds()).delete();
    }

    private static boolean isMarked(ConfigMap anchor) {
        return anchor.getMetadata().getAnnotations() != null
                && CANCELLED.equals(anchor.getMetadata().getAnnotations().get(CANCELLED_ANNOTATION));
    }

    private static List<String> names(List<HasMetadata> objects) {
        return objects.stream().map(object -> object.getClass().getSimpleName() + "/" + object.getMetadata().getName()).toList();
    }

    private static void pause(String runId) {
        try {
            Thread.sleep(POLL);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while destroying the unit of run " + runId, e);
        }
    }
}
