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
 * Says at startup when proxied units could not start their model proxy. The image is built locally and is not on a
 * registry yet, so a deployment that upgraded without building it would otherwise learn of it from its first failed
 * review run. A missing image stops the proxied lane, not the worker: it is logged at ERROR naming
 * {@value #VARIABLE}, builds run on, and each proxied run fails as {@code IMAGE_UNAVAILABLE} before anything is
 * created ({@link ModelProxyResults#imageMissing}). An unset image is a configuration fault and still refuses startup.
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

    /** Logs when a proxied unit could not start its model proxy; reads the runtime only when one could be built. */
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
            // Logged, not thrown: the lane stops, the worker does not. Every proxied run fails as IMAGE_UNAVAILABLE
            // naming the variable (ModelProxyResults.imageMissing) while builds run on. No stack and no message: a
            // pull's message can quote a registry reply.
            LOG.errorf("spire.run.credential-proxy.image (%s) names %s, which this worker's runtime cannot find or"
                    + " pull, so every %s run unit will fail as IMAGE_UNAVAILABLE until it is built (deploy/.env.example"
                    + " says how) or SPIRE_RUN_CREDENTIAL_PROXY_MODE is OFF", VARIABLE, image, mode);
        }
    }

    private IllegalStateException refusal(String problem) {
        return new IllegalStateException("spire.run.credential-proxy.image (" + VARIABLE + ") " + problem
                + ": the model proxy holds the credential of every " + mode + " run unit, so they cannot start"
                + " without it. Build the spire-model-proxy image (deploy/.env.example says how),"
                + " or set SPIRE_RUN_CREDENTIAL_PROXY_MODE=OFF.");
    }
}
