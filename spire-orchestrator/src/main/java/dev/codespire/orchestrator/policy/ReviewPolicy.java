package dev.codespire.orchestrator.policy;

import io.quarkus.runtime.Startup;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

/**
 * The global review mode — first-contact safety.
 * {@code spire.review.mode=observe} registers each PR (persisted and shown on the
 * dashboard) but emits NO action commands: no diff fetch, no LLM call, no
 * comments. {@code active} runs the full pipeline. The per-provider author
 * allowlist lives in the provider registry, not here.
 */
@Startup // eager: log the posture at boot so a first-contact run is visibly safe
@ApplicationScoped
public class ReviewPolicy {

    private static final Logger LOG = Logger.getLogger(ReviewPolicy.class);

    private final boolean observeOnly;

    @Inject
    public ReviewPolicy(@ConfigProperty(name = "spire.review.mode", defaultValue = "active") String mode) {
        this.observeOnly = "observe".equalsIgnoreCase(mode == null ? "" : mode.trim());
        LOG.infof("Review policy: mode=%s",
                observeOnly ? "OBSERVE (register only, no diff/LLM/comments)" : "active");
    }

    /** True when a run must be registered but emit no action commands. */
    public boolean observeOnly() {
        return observeOnly;
    }
}
