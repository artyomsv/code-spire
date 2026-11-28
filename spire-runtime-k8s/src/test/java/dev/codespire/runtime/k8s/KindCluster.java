package dev.codespire.runtime.k8s;

import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Namespace;
import io.fabric8.kubernetes.api.model.NamespaceBuilder;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientBuilder;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * The cluster the Kubernetes arm's tests run against: a kind cluster the CI job started and loaded the
 * test images into. The client reads the kubeconfig the job wrote, so there is nothing to configure.
 *
 * <p>Namespaces here are lower case ({@code test-...}) because Kubernetes refuses upper case in a name.
 * The {@code TEST} marker the repository uses for test data is kept in a label instead.
 */
final class KindCluster {

    static final String UNIT_IMAGE = "spire-test-unit:TEST";

    private static final String CANARY_NAMESPACE = "test-canary";

    private static final String CANARY_POD = "test-canary";

    static final int CANARY_PORT = 8080;

    /** Exit code of {@link #tryDropOrReach}: every attempt ran into the timeout, so packets were dropped. */
    static final int EXIT_DROPPED = 1;

    /** Exit code of {@link #tryDropOrReach}: an attempt failed fast, so something refused it, not a policy. */
    static final int EXIT_REFUSED = 99;

    private static KubernetesClient client;

    private static String provenCanary;

    private KindCluster() {
    }

    /** The one client of this JVM. */
    static synchronized KubernetesClient client() {
        if (client == null) {
            client = new KubernetesClientBuilder().build();
        }
        return client;
    }

    /**
     * Creates a namespace named {@code prefix} plus a random suffix, with a default-deny policy for
     * ingress and egress, and returns its name.
     */
    static String newNamespace(String prefix) {
        String name = (prefix + "-" + UUID.randomUUID().toString().substring(0, 8)).toLowerCase();
        Namespace namespace = new NamespaceBuilder()
                .withNewMetadata().withName(name).addToLabels("spire-test", "TEST").endMetadata()
                .build();
        client().namespaces().resource(namespace).create();
        NetworkPolicy denyAll = new NetworkPolicyBuilder()
                .withNewMetadata().withName("default-deny").withNamespace(name).endMetadata()
                .withNewSpec()
                .withNewPodSelector().endPodSelector()
                .withPolicyTypes("Ingress", "Egress")
                .endSpec()
                .build();
        client().network().v1().networkPolicies().inNamespace(name).resource(denyAll).create();
        return name;
    }

    /**
     * A pod that listens, in the namespace {@code test-canary}, as {@code ip:port}. Created on first use
     * and reused. The namespace has no policy, so it accepts any caller; whether a caller can reach it is
     * decided only by the caller's own namespace.
     */
    static synchronized String canaryTarget() {
        if (provenCanary != null) {
            return provenCanary;
        }
        if (client().namespaces().withName(CANARY_NAMESPACE).get() == null) {
            client().namespaces().resource(new NamespaceBuilder()
                    .withNewMetadata().withName(CANARY_NAMESPACE).addToLabels("spire-test", "TEST").endMetadata()
                    .build()).create();
        }
        Pod existing = client().pods().inNamespace(CANARY_NAMESPACE).withName(CANARY_POD).get();
        if (existing == null) {
            Pod canary = new PodBuilder()
                    .withNewMetadata().withName(CANARY_POD).withNamespace(CANARY_NAMESPACE).endMetadata()
                    .withNewSpec()
                    .withRestartPolicy("Always")
                    .addNewContainer()
                    .withName("canary")
                    .withImage(UNIT_IMAGE)
                    .withImagePullPolicy("IfNotPresent")
                    .withCommand("/bin/sh", "-c",
                            "nc -ll -p " + CANARY_PORT + " -e /bin/true")
                    .endContainer()
                    .endSpec()
                    .build();
            client().pods().inNamespace(CANARY_NAMESPACE).resource(canary).create();
        }
        Pod running = client().pods().inNamespace(CANARY_NAMESPACE).withName(CANARY_POD)
                .waitUntilCondition(pod -> pod != null && pod.getStatus() != null
                                && pod.getStatus().getPodIP() != null
                                && "Running".equals(pod.getStatus().getPhase()),
                        120, TimeUnit.SECONDS);
        String target = running.getStatus().getPodIP() + ":" + CANARY_PORT;
        proveListening(target);
        provenCanary = target;
        return target;
    }

    /**
     * Connects to the canary from its own namespace, which has no policy, and fails unless it answers. A
     * running pod is not a listening one, so every later "cannot reach" result rests on this proof: without
     * it a refused connection would read the same as a dropped one.
     */
    private static void proveListening(String target) {
        String probe = "test-probe-" + UUID.randomUUID().toString().substring(0, 8);
        int exit = runToCompletion(CANARY_NAMESPACE, probe, "test-probe", tryUntilConnected(target),
                Duration.ofSeconds(90));
        if (exit != 0) {
            throw new IllegalStateException("The canary " + target + " never accepted a connection from its own "
                    + "namespace (exit " + exit + "), so no reachability result can be trusted.");
        }
    }

    /** The cluster DNS Service's address, {@code clusterIP:53}. */
    static String dnsAddress() {
        Service dns = client().services().inNamespace("kube-system").withName("kube-dns").get();
        if (dns == null) {
            throw new IllegalStateException("kube-system has no kube-dns Service");
        }
        return dns.getSpec().getClusterIP() + ":53";
    }

    /**
     * Runs one pod to completion in {@code namespace} and returns its container's exit code. The pod
     * runs {@code command} in the test unit image, and the call fails if it does not finish in time.
     */
    static int runToCompletion(String namespace, String podName, String label, String command, Duration limit) {
        Pod pod = new PodBuilder()
                .withNewMetadata().withName(podName).withNamespace(namespace)
                .addToLabels("app", label).addToLabels("spire-test", "TEST").endMetadata()
                .withNewSpec()
                .withRestartPolicy("Never")
                .addNewContainer()
                .withName("unit")
                .withImage(UNIT_IMAGE)
                .withImagePullPolicy("IfNotPresent")
                .withCommand("/bin/sh", "-c", command)
                .endContainer()
                .endSpec()
                .build();
        client().pods().inNamespace(namespace).resource(pod).create();
        Pod done = client().pods().inNamespace(namespace).withName(podName)
                .waitUntilCondition(p -> p != null && p.getStatus() != null
                                && ("Succeeded".equals(p.getStatus().getPhase())
                                || "Failed".equals(p.getStatus().getPhase())),
                        limit.toSeconds(), TimeUnit.SECONDS);
        List<ContainerStatus> statuses = done.getStatus().getContainerStatuses();
        if (statuses == null || statuses.isEmpty() || statuses.get(0).getState().getTerminated() == null) {
            throw new IllegalStateException("pod " + podName + " finished with no exit code: " + done.getStatus());
        }
        return statuses.get(0).getState().getTerminated().getExitCode();
    }

    /** The command that tries the canary once, with a two-second connect limit. */
    static String tryOnce(String target) {
        int colon = target.lastIndexOf(':');
        return "nc -w 2 " + target.substring(0, colon) + " " + target.substring(colon + 1) + " </dev/null";
    }

    /**
     * The command that tries the canary three times, two seconds each. Exit 0 on the first connection;
     * {@link #EXIT_REFUSED} if an attempt failed in under two seconds (a refusal or a reset, not a drop);
     * {@link #EXIT_DROPPED} only when all three attempts ran the full timeout. A policy drop makes a connect
     * hang until the timeout, a refusal returns at once, so only the first proves enforcement.
     */
    static String tryDropOrReach(String target) {
        return "for i in 1 2 3; do s=$(date +%s); " + tryOnce(target) + "; rc=$?; e=$(date +%s); "
                + "[ $rc -eq 0 ] && exit 0; [ $((e-s)) -lt 2 ] && exit " + EXIT_REFUSED + "; done; exit "
                + EXIT_DROPPED;
    }

    /** The command that tries the canary for up to about 40 seconds and succeeds on the first connection. */
    static String tryUntilConnected(String target) {
        return "for i in $(seq 1 20); do " + tryOnce(target) + " && exit 0; sleep 2; done; exit 1";
    }
}
