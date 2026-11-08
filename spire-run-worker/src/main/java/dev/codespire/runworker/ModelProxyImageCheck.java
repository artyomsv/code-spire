package dev.codespire.runworker;

import dev.codespire.runtime.EgressPolicy;
import dev.codespire.runtime.RunRuntime;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.function.Supplier;

/**
 * Refuses to start a worker whose proxied units could not start their model proxy, like {@link PublisherImageCheck}
 * does for the publisher. The image is built locally and is not on a registry yet, so a deployment that upgraded
 * without building it would otherwise fail its first review run, after the review was dispatched.
 *
 * <p>With {@code spire.run.egress.mode=off} no unit is proxied (a model proxy reaches its vendor only through the
 * unit's egress proxy), so the image is not looked at and one WARN names both settings instead: the operator asked for
 * the credential to be held away from the agent and is not getting it.
 */
@ApplicationScoped
public class ModelProxyImageCheck {

    private static final Logger LOG = Logger.getLogger(ModelProxyImageCheck.class);

    static final String VARIABLE = "SPIRE_RUN_CREDENTIAL_PROXY_IMAGE";

    @ConfigProperty(name = "spire.run.credential-proxy.mode")
    CredentialProxyMode mode;

    @ConfigProperty(name = "spire.run.credential-proxy.image")
    String image;

    @ConfigProperty(name = "spire.run.egress.mode")
    EgressPolicy.Mode egressMode;

    /** An Instance, so a worker with the proxy off never creates the runtime for this check. */
    @Inject
    Instance<RunRuntime> runtimes;

    void check(@Observes StartupEvent event) {
        verify(runtimes::get);
    }

    /** Throws when a proxied unit could not start its model proxy; reads the runtime only when one could be built. */
    void verify(Supplier<RunRuntime> runtime) {
        if (mode == CredentialProxyMode.OFF) {
            return;
        }
        if (egressMode == EgressPolicy.Mode.OFF) {
            LOG.warnf("spire.run.credential-proxy.mode is %s but spire.run.egress.mode is off, so no run unit is"
                    + " proxied and every agent holds its model credential; set SPIRE_RUN_EGRESS_MODE to report or"
                    + " enforce, or SPIRE_RUN_CREDENTIAL_PROXY_MODE to OFF to say so", mode);
            return;
        }
        if (image == null || image.isBlank()) {
            throw refusal("is not set");
        }
        try {
            runtime.get().describeImage(image);
        } catch (RuntimeException e) {
            throw refusal("names " + image + ", which this worker's runtime cannot find or pull ("
                    + e.getClass().getSimpleName() + ")");
        }
    }

    private IllegalStateException refusal(String problem) {
        return new IllegalStateException("spire.run.credential-proxy.image (" + VARIABLE + ") " + problem
                + ": the model proxy holds the credential of every " + mode + " run unit, so they cannot start"
                + " without it. Build it with ./gradlew :spire-model-proxy:installDist && docker build -t"
                + " spire-model-proxy:latest spire-model-proxy, or set SPIRE_RUN_CREDENTIAL_PROXY_MODE=OFF.");
    }
}
