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
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.time.Duration;
import java.util.ArrayList;
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

    private static final System.Logger LOG = System.getLogger(KubernetesRunRuntime.class.getName());

    /** The Docker arm's stop grace. */
    static final Duration STOP_GRACE = Duration.ofSeconds(5);

    static final String CANCELLED_BEFORE_START = "the run was cancelled before its unit pod was created";

    private final KubernetesClient client;

    private final KubernetesRuntimeConfig config;

    private final RegistryImageReader images;

    private final UnitStart start;

    private final UnitTeardown teardown;

    private final Attachments attachments = new Attachments();

    private final UnitSalvage unitSalvage;

    public KubernetesRunRuntime(KubernetesClient client, KubernetesRuntimeConfig config, RegistryImageReader images) {
        this.client = Objects.requireNonNull(client, "client");
        this.config = Objects.requireNonNull(config, "config");
        this.images = Objects.requireNonNull(images, "images");
        this.start = new UnitStart(client, config);
        this.teardown = new UnitTeardown(client, config.namespace());
        this.unitSalvage = new UnitSalvage(client, config, DRAIN_WINDOW);
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
        if (refusal.isPresent()) {
            throw new UnitRefused(runId, refusal.get());
        }
        OwnerReference anchor = createAnchor(spec);
        for (Secret secret : UnitSecrets.of(spec, config, anchor)) {
            client.secrets().inNamespace(config.namespace()).resource(secret).create();
        }
        client.network().v1().networkPolicies().inNamespace(config.namespace())
                .resource(UnitPolicies.unit(runId, config, anchor)).create();
        client.network().v1().networkPolicies().inNamespace(config.namespace())
                .resource(UnitPolicies.proxy(runId, config, anchor)).create();
        client.pods().inNamespace(config.namespace()).resource(ProxyPod.of(spec, config, anchor)).create();
        try {
            String proxyIp = start.awaitProxies(runId);
            if (teardown.isCancelled(runId)) {
                throw new UnitNotStarted(CANCELLED_BEFORE_START, false);
            }
            client.pods().inNamespace(config.namespace()).resource(UnitPod.of(spec, config, anchor, proxyIp)).create();
            start.awaitAgent(runId);
            // A cancel that marked the anchor while the agent started finds no unit pod to stop if this returned.
            if (teardown.isCancelled(runId)) {
                throw new UnitNotStarted(CANCELLED_BEFORE_START, false);
            }
        } catch (RuntimeException failed) {
            // Whatever failed, a pod left behind may still start a paid agent, or hold the model credential, with
            // nobody attached to stop it. The anchor and the Secrets stay, so the unit is discoverable.
            teardown.stopPods(runId, failed);
            throw failed;
        }
        dropFetchSecret(runId);
        return new RunHandle(runId, UnitNames.anchor(runId));
    }

    /**
     * The agent starts only after the fetch has ended, so the forge credential is no longer needed. Best effort: the
     * agent runs already, and destroy deletes the Secret anyway.
     */
    private void dropFetchSecret(String runId) {
        try {
            teardown.deleteSecret(UnitNames.environmentSecret(runId, "fetch"));
        } catch (KubernetesClientException kept) {
            // Kept until destroy; failing create here would leave a running agent with no handle.
        }
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
                .withController(true)
                .withBlockOwnerDeletion(true)
                .build();
    }

    /**
     * Follows the container's log from its start and returns when the stream ends, as the Docker arm does. It also
     * returns when the unit is cancelled or destroyed, and when the calling thread is interrupted.
     */
    @Override
    public void attach(RunHandle handle, LogChannel channel, Consumer<String> lines) {
        String container = channel == LogChannel.AGENT ? UnitPod.AGENT : UnitPod.PUBLISHER;
        try (LogWatch watch = client.pods().inNamespace(config.namespace())
                .withName(UnitNames.unitPod(handle.runId())).inContainer(container).watchLog()) {
            InputStream output = watch.getOutput();
            attachments.follow(handle.runId(), output, () -> closeStream(output, watch), lines);
        }
    }

    /** The watch's own close leaves the response body open, so the body is closed first. */
    private static void closeStream(InputStream output, LogWatch watch) {
        try {
            output.close();
        } catch (IOException alreadyBroken) {
            // The read ends either way; the attach has returned already.
        }
        watch.close();
    }

    /** Both pods and every Secret go; the anchor stays, marked first, so the unit is still discoverable. */
    @Override
    public void cancel(RunHandle handle) {
        String runId = handle.runId();
        teardown.markCancelled(runId);
        attachments.closeAll(runId);
        teardown.deletePods(runId);
        teardown.deleteSecrets(runId);
    }

    @Override
    public void steer(RunHandle handle, String instruction) {
        throw new UnsupportedOperationException("run " + handle.runId() + ": the Kubernetes arm cannot steer a run");
    }

    /** See {@link UnitSalvage}: waits for the unit pod's deadline plus the drain window. Deletes nothing. */
    @Override
    public Finalization salvage(RunHandle handle) {
        return unitSalvage.salvage(handle.runId());
    }

    @Override
    public void destroy(RunHandle handle) {
        attachments.closeAll(handle.runId());
        teardown.destroy(handle.runId());
    }

    /** Every anchor in the namespace; the run id is its annotation. */
    @Override
    public List<RunHandle> discoverUnits() {
        List<RunHandle> units = new ArrayList<>();
        for (ConfigMap anchor : client.configMaps().inNamespace(config.namespace())
                .withLabel(UnitNames.ROLE_LABEL, ANCHOR_ROLE).list().getItems()) {
            Map<String, String> annotations = anchor.getMetadata().getAnnotations();
            String runId = annotations == null ? null : annotations.get(UnitNames.RUN_ID_ANNOTATION);
            if (runId == null) {
                continue;
            }
            String name = anchor.getMetadata().getName();
            if (!UnitNames.anchor(runId).equals(name)) {
                // Not a unit this arm made for that run id: acting on it could destroy another unit's objects.
                LOG.log(System.Logger.Level.WARNING, "skipping the anchor {0}: its run id {1} names the anchor {2}",
                        name, runId, UnitNames.anchor(runId));
                continue;
            }
            units.add(new RunHandle(runId, name));
        }
        return List.copyOf(units);
    }

    @Override
    public Duration drainWindow() {
        return DRAIN_WINDOW;
    }

    @Override
    public ImageDescription describeImage(String image) {
        return images.describe(image);
    }
}
