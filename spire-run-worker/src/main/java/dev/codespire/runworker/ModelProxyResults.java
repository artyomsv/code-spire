package dev.codespire.runworker;

import dev.codespire.contract.event.RunFailureCause;
import dev.codespire.runtime.ModelProxyReport;
import dev.codespire.runtime.RunHandle;
import dev.codespire.runtime.RunRuntime;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

/**
 * What a unit's model proxy saw, and what it says about a run whose agent failed (harness reviews step 3).
 *
 * <p>Like {@link EgressResults}, it decides the cause only for a run that failed anyway, and it comes first: a 401 on a
 * model call is the vendor refusing the credential, and {@code CREDENTIAL_REJECTED} is what marks that member of the
 * harness credential pool ({@code RunCredentialFeedback}). A refused path is a harness calling something its arm does
 * not declare; the proxy logs it as a fixed token, never its target, and that token is what the detail names. A 403
 * is neither: it can be a challenge or an entitlement answer, the run keeps the adapter's own cause
 * and detail, and marking would retire a healthy seat until an operator replaces it.
 */
final class ModelProxyResults {

    private static final Logger LOG = Logger.getLogger(ModelProxyResults.class);

    /** How many refused paths a failure detail names. */
    private static final int PATHS_NAMED = 3;

    private ModelProxyResults() {
    }

    /** Read once the agent is gone. A failed read is not a run fault: the run is decided without it. */
    static ModelProxyReport read(RunRuntime runtime, RunHandle handle) {
        try {
            return runtime.modelProxyOf(handle);
        } catch (RuntimeException e) {
            LOG.warnf("run %s: the model proxy's record could not be read (%s); the run is decided without it",
                    handle.runId(), e.getClass().getSimpleName());
            return ModelProxyReport.NONE;
        }
    }

    /**
     * Why a unit's model proxy cannot start, asked BEFORE the unit is created: the runtime would otherwise find out
     * after init had cloned, leaving a half-built unit behind and reporting a runtime fault. Empty for a unit with no
     * model proxy, which is never held up by its image. The image is looked up with the same pull a run uses.
     */
    static Optional<String> imageMissing(RunRuntime runtime, dev.codespire.runtime.RunUnitSpec unit) {
        if (unit == null || unit.modelProxy() == null) {
            return Optional.empty();
        }
        String image = unit.modelProxy().image();
        try {
            runtime.describeImage(image);
            return Optional.empty();
        } catch (RuntimeException e) {
            // The exception's class, never its message: a pull's message can quote a registry reply.
            return Optional.of("the model proxy image " + image + " (" + ModelProxyImageCheck.VARIABLE
                    + ") cannot be found or pulled (" + e.getClass().getSimpleName() + "); build it, or set"
                    + " SPIRE_RUN_CREDENTIAL_PROXY_MODE=OFF");
        }
    }

    /** The cause and detail of a failed run the model proxy explains, or empty when it explains nothing. */
    static Optional<Explained> explain(ModelProxyReport report, String agentDetail) {
        if (report.rejected() > 0) {
            return Optional.of(new Explained(RunFailureCause.CREDENTIAL_REJECTED,
                    "the model vendor refused the run's credential (" + report.rejected()
                            + " model call(s) answered 401); then: " + agentDetail));
        }
        List<String> refused = report.refusedPaths();
        if (refused.isEmpty()) {
            return Optional.empty();
        }
        String named = String.join(", ", refused.subList(0, Math.min(PATHS_NAMED, refused.size())));
        String more = refused.size() > PATHS_NAMED ? " and " + (refused.size() - PATHS_NAMED) + " more" : "";
        return Optional.of(new Explained(RunFailureCause.BLOCKED_EGRESS, "the model proxy refused a request the"
                + " harness does not declare (" + named + more + "); then: " + agentDetail));
    }

    record Explained(RunFailureCause cause, String detail) {
    }
}
