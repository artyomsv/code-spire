package dev.codespire.runtime.k8s;

import dev.codespire.runtime.Finalization;
import dev.codespire.runtime.ImageDescription;
import dev.codespire.runtime.LogChannel;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunRuntime;
import dev.codespire.runtime.RunUnitSpec;
import dev.codespire.runtime.RuntimeCapabilities;
import dev.codespire.runtime.RuntimeType;
import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.Secret;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.LogWatch;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * The Kubernetes arm of {@link RunRuntime}: a review unit as two pods under default-deny network policy
 * (docs/superpowers/specs/2026-11-28-m5-kubernetes-review-runtime-design.md). Every object is created in the one
 * configured namespace and owned by the unit's anchor ConfigMap.
 */
public final class KubernetesRunRuntime implements RunRuntime {

    static final String ANCHOR_ROLE = "anchor";

    static final String WALL_CLOCK_ANNOTATION = "dev.codespire/wall-clock-seconds";

    /** The unit pod's termination grace plus a margin for the last log read. */
    static final Duration DRAIN_WINDOW = Duration.ofSeconds(60);

    static final String REVIEWS_ONLY = "kubernetes_reviews_only";

    private final KubernetesClient client;

    private final KubernetesRuntimeConfig config;

    private final RegistryImageReader images;

    private final UnitStart start;

    public KubernetesRunRuntime(KubernetesClient client, KubernetesRuntimeConfig config, RegistryImageReader images) {
        this.client = Objects.requireNonNull(client, "client");
        this.config = Objects.requireNonNull(config, "config");
        this.images = Objects.requireNonNull(images, "images");
        this.start = new UnitStart(client, config);
    }

    @Override
    public RuntimeType type() {
        return RuntimeType.KUBERNETES;
    }

    @Override
    public RuntimeCapabilities capabilities() {
        return new RuntimeCapabilities(true, true, false, false, false, true);
    }

    @Override
    public Optional<String> buildLaneRefusal() {
        return Optional.of(REVIEWS_ONLY);
    }

    /**
     * Refuses before any object exists; then the anchor, the Secrets, both policies, the proxy pod, and only once
     * its proxies listen the unit pod, which needs the proxy pod's IP. Returns once the agent runs.
     */
    @Override
    public RunHandle create(RunUnitSpec spec) {
        String runId = spec.runId();
        Optional<String> refusal = UnitRefusals.of(spec);
        if (refusal.isPresent() && false) {
            throw new UnitRefused(runId, refusal.get());
        }
        for (Secret secret : UnitSecrets.of(spec, config, new OwnerReferenceBuilder().withApiVersion("v1")
                .withKind("ConfigMap").withName(UnitNames.anchor(runId)).withUid("TEST-mutation").build())) {
            secret.getMetadata().setOwnerReferences(List.of());
            client.secrets().inNamespace(config.namespace()).resource(secret).create();
        }
        OwnerReference anchor = createAnchor(spec);
        client.network().v1().networkPolicies().inNamespace(config.namespace())
                .resource(UnitPolicies.unit(runId, config, anchor)).create();
        client.network().v1().networkPolicies().inNamespace(config.namespace())
                .resource(UnitPolicies.proxy(runId, config, anchor)).create();
        client.pods().inNamespace(config.namespace()).resource(ProxyPod.of(spec, config, anchor)).create();
        String proxyIp = start.awaitProxies(runId);
        client.pods().inNamespace(config.namespace()).resource(UnitPod.of(spec, config, anchor, proxyIp)).create();
        start.awaitAgent(runId);
        return new RunHandle(runId, UnitNames.anchor(runId));
    }

    /** An existing anchor is an error: a redelivered command is stopped earlier, and nothing here is adopted. */
    private OwnerReference createAnchor(RunUnitSpec spec) {
        String runId = spec.runId();
        Map<String, String> annotations = new LinkedHashMap<>();
        annotations.put(UnitNames.RUN_ID_ANNOTATION, runId);
        annotations.put(WALL_CLOCK_ANNOTATION, String.valueOf(spec.wallClock().toSeconds()));
        ConfigMap anchor = new ConfigMapBuilder()
                .withNewMetadata()
                .withName(UnitNames.anchor(runId))
                .withNamespace(config.namespace())
                .withLabels(UnitNames.labels(runId, ANCHOR_ROLE))
                .withAnnotations(annotations)
                .endMetadata()
                .build();
        ConfigMap created;
        try {
            created = client.configMaps().inNamespace(config.namespace()).resource(anchor).create();
        } catch (KubernetesClientException e) {
            if (e.getCode() == HttpURLConnection.HTTP_CONFLICT) {
                throw new IllegalStateException("the unit " + UnitNames.anchor(runId) + " of run " + runId
                        + " already exists in namespace " + config.namespace() + "; a unit is never adopted", e);
            }
            throw e;
        }
        return new OwnerReferenceBuilder()
                .withApiVersion("v1")
                .withKind("ConfigMap")
                .withName(created.getMetadata().getName())
                .withUid(created.getMetadata().getUid())
                .build();
    }

    /** Follows the container's log from its start and returns when the stream ends, as the Docker arm does. */
    @Override
    public void attach(RunHandle handle, LogChannel channel, Consumer<String> lines) {
        String container = channel == LogChannel.AGENT ? UnitPod.AGENT : UnitPod.PUBLISHER;
        try (LogWatch watch = client.pods().inNamespace(config.namespace())
                .withName(UnitNames.unitPod(handle.runId())).inContainer(container).watchLog()) {
            PodLogs.follow(watch.getOutput(), lines);
        }
    }

    @Override
    public void cancel(RunHandle handle) {
        throw notYet("cancel");
    }

    @Override
    public void steer(RunHandle handle, String instruction) {
        throw new UnsupportedOperationException("run " + handle.runId() + ": the Kubernetes arm cannot steer a run");
    }

    @Override
    public Finalization salvage(RunHandle handle) {
        throw notYet("salvage");
    }

    @Override
    public void destroy(RunHandle handle) {
        throw notYet("destroy");
    }

    @Override
    public List<RunHandle> discoverUnits() {
        throw notYet("discoverUnits");
    }

    @Override
    public Duration drainWindow() {
        return DRAIN_WINDOW;
    }

    @Override
    public ImageDescription describeImage(String image) {
        return images.describe(image);
    }

    private static UnsupportedOperationException notYet(String method) {
        return new UnsupportedOperationException("the Kubernetes arm has no " + method + " yet");
    }
}
