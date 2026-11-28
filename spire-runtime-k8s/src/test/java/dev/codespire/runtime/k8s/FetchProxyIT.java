package dev.codespire.runtime.k8s;

import dev.codespire.runtime.EgressPolicy;
import dev.codespire.runtime.EnterpriseEnvironment;
import dev.codespire.runtime.TinyproxyConfig;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.api.model.PodBuilder;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicy;
import io.fabric8.kubernetes.api.model.networking.v1.NetworkPolicyBuilder;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * The fetch proxy of the Kubernetes unit: it forwards to the one host it allows, and a connection to its close
 * port stops it for good.
 *
 * <p>The allowed and denied hosts are real public names ({@code example.com}, {@code example.org}) that this test
 * owns no data on. A GitHub runner's cluster can resolve and reach them, which a made-up host name could not; the
 * test sends one plain GET to each and nothing else.
 */
@Tag("kind")
class FetchProxyIT {

    private static final String PROXY_IMAGE = "spire-egress-proxy:TEST";

    private static final String ALLOWED_HOST = "example.com";

    private static final String DENIED_HOST = "example.org";

    private static final int PROXY_PORT = 8889;

    private static final int CLOSE_PORT = 8890;

    @Test
    void itForwardsToTheAllowedHostAndStopsForGoodOnClose() {
        String namespace = KindCluster.newNamespace("test-fetch");
        createPolicies(namespace);
        startProxy(namespace, "test-fetch-a", 900);
        Pod running = KindCluster.client().pods().inNamespace(namespace).withName("test-fetch-a")
                .waitUntilCondition(p -> p != null && p.getStatus() != null
                                && p.getStatus().getPodIP() != null && "Running".equals(p.getStatus().getPhase()),
                        120, TimeUnit.SECONDS);
        String ip = running.getStatus().getPodIP();

        // Exit codes name the failed step: 11 the allowed host was not forwarded, 12 the other host was,
        // 14 the proxy still answers after the close (a close that did not connect shows here).
        String script = "export http_proxy=http://" + ip + ":" + PROXY_PORT + "; "
                + "wget -T 20 -O /dev/null http://" + ALLOWED_HOST + "/ || exit 11; "
                + "wget -T 20 -O /dev/null http://" + DENIED_HOST + "/ && exit 12; "
                + "nc -w 5 " + ip + " " + CLOSE_PORT + " </dev/null; "
                + "sleep 3; "
                + "nc -w 3 " + ip + " " + PROXY_PORT + " </dev/null && exit 14; "
                + "exit 0";
        int exit = KindCluster.runToCompletion(namespace, "test-fetch-b", "test-fetch-b", script,
                Duration.ofSeconds(180));
        String logs = exit == 0 ? "" : " client log: " + podLog(namespace, "test-fetch-b")
                + " proxy log: " + podLog(namespace, "test-fetch-a");
        assertEquals(0, exit, "the client's step failed (11 allowed host not forwarded, 12 other host forwarded, "
                + "14 proxy still answering after the close)" + logs);

        Pod done = KindCluster.client().pods().inNamespace(namespace).withName("test-fetch-a")
                .waitUntilCondition(p -> p != null && p.getStatus() != null
                        && "Succeeded".equals(p.getStatus().getPhase()), 60, TimeUnit.SECONDS);
        ContainerStatus status = done.getStatus().getContainerStatuses().get(0);
        assertNotNull(status.getState().getTerminated(), "the proxy container must be terminated");
        assertEquals(0, status.getState().getTerminated().getExitCode());
        assertEquals(0, status.getRestartCount());
    }

    @Test
    void itStopsByItselfAtTheDeadline() {
        String namespace = KindCluster.newNamespace("test-fetch");
        startProxy(namespace, "test-fetch-d", 5);
        Pod done = KindCluster.client().pods().inNamespace(namespace).withName("test-fetch-d")
                .waitUntilCondition(p -> p != null && p.getStatus() != null
                        && "Succeeded".equals(p.getStatus().getPhase()), 30, TimeUnit.SECONDS);
        assertEquals(0, done.getStatus().getContainerStatuses().get(0).getState().getTerminated().getExitCode());
    }

    private static void startProxy(String namespace, String name, int deadlineSeconds) {
        EgressPolicy policy = new EgressPolicy(EgressPolicy.Mode.ENFORCE, Set.of(ALLOWED_HOST));
        String conf = TinyproxyConfig.configFor(policy, EnterpriseEnvironment.NONE,
                new TinyproxyConfig.Listener(PROXY_PORT, "0.0.0.0/0"));
        Pod pod = new PodBuilder()
                .withNewMetadata().withName(name).withNamespace(namespace)
                .addToLabels("app", "test-fetch-a").addToLabels("spire-test", "TEST").endMetadata()
                .withNewSpec()
                .withRestartPolicy("Never")
                .addNewContainer()
                .withName("proxy")
                .withImage(PROXY_IMAGE)
                .withImagePullPolicy("IfNotPresent")
                .withCommand("spire-fetch-proxy", String.valueOf(CLOSE_PORT), String.valueOf(deadlineSeconds))
                .addNewEnv().withName("SPIRE_EGRESS_CONF").withValue(conf).endEnv()
                .addNewEnv().withName("SPIRE_EGRESS_FILTER").withValue(TinyproxyConfig.filterFor(policy)).endEnv()
                .withNewSecurityContext()
                .withReadOnlyRootFilesystem(true)
                .withAllowPrivilegeEscalation(false)
                .withNewCapabilities().addToDrop("ALL").endCapabilities()
                .withNewSeccompProfile().withType("RuntimeDefault").endSeccompProfile()
                .endSecurityContext()
                .addNewVolumeMount().withName("tmp").withMountPath("/tmp").endVolumeMount()
                .endContainer()
                .addNewVolume().withName("tmp").withNewEmptyDir().withMedium("Memory").endEmptyDir().endVolume()
                .endSpec()
                .build();
        KindCluster.client().pods().inNamespace(namespace).resource(pod).create();
    }

    /** Proxy: ingress from the client, egress to DNS and port 80. Client: egress to the proxy's two ports. */
    private static void createPolicies(String namespace) {
        NetworkPolicy proxy = new NetworkPolicyBuilder()
                .withNewMetadata().withName("proxy").withNamespace(namespace).endMetadata()
                .withNewSpec()
                .withNewPodSelector().addToMatchLabels("app", "test-fetch-a").endPodSelector()
                .withPolicyTypes("Ingress", "Egress")
                .addNewIngress()
                .addNewFrom().withNewPodSelector().addToMatchLabels("app", "test-fetch-b").endPodSelector().endFrom()
                .addNewPort().withProtocol("TCP").withPort(new IntOrString(PROXY_PORT)).endPort()
                .addNewPort().withProtocol("TCP").withPort(new IntOrString(CLOSE_PORT)).endPort()
                .endIngress()
                .addNewEgress()
                .addNewTo().withNewIpBlock().withCidr("0.0.0.0/0").endIpBlock().endTo()
                .addNewPort().withProtocol("TCP").withPort(new IntOrString(80)).endPort()
                .addNewPort().withProtocol("UDP").withPort(new IntOrString(53)).endPort()
                .addNewPort().withProtocol("TCP").withPort(new IntOrString(53)).endPort()
                .endEgress()
                .endSpec()
                .build();
        NetworkPolicy client = new NetworkPolicyBuilder()
                .withNewMetadata().withName("client").withNamespace(namespace).endMetadata()
                .withNewSpec()
                .withNewPodSelector().addToMatchLabels("app", "test-fetch-b").endPodSelector()
                .withPolicyTypes("Egress")
                .addNewEgress()
                .addNewTo().withNewPodSelector().addToMatchLabels("app", "test-fetch-a").endPodSelector().endTo()
                .addNewPort().withProtocol("TCP").withPort(new IntOrString(PROXY_PORT)).endPort()
                .addNewPort().withProtocol("TCP").withPort(new IntOrString(CLOSE_PORT)).endPort()
                .endEgress()
                .endSpec()
                .build();
        KindCluster.client().network().v1().networkPolicies().inNamespace(namespace).resource(proxy).create();
        KindCluster.client().network().v1().networkPolicies().inNamespace(namespace).resource(client).create();
    }

    private static String podLog(String namespace, String name) {
        try {
            KindCluster.client().pods().inNamespace(namespace).withName(name)
                    .waitUntilCondition(p -> p != null && p.getStatus() != null
                            && ("Succeeded".equals(p.getStatus().getPhase())
                            || "Failed".equals(p.getStatus().getPhase())), 90, TimeUnit.SECONDS);
        } catch (io.fabric8.kubernetes.client.KubernetesClientTimeoutException stillRunning) {
            // The log so far is still the most useful thing to report.
        }
        return KindCluster.client().pods().inNamespace(namespace).withName(name).getLog();
    }
}
