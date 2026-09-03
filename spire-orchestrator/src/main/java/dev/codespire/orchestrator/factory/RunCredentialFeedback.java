package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunFailureCause;
import dev.codespire.contract.event.RunResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * What a run's outcome says about the key it ran with (FR-F12).
 *
 * <p>The pool cannot learn this for itself. The orchestrator hands a member's key to a sandbox and
 * never calls the model; the agent does, inside the container, and the only thing that comes back is
 * the run's classified failure. So the pool's health is downstream of the run's, and this is the one
 * place that translates between them.
 *
 * <p><b>Only a refusal marks a member.</b> {@link RunFailureCause#CREDENTIAL_REJECTED} is the
 * provider saying the key is wrong, revoked or out of credit — an answer, and one that will be the
 * same next time, so the member leaves the pool until an operator replaces it.
 *
 * <p>{@link RunFailureCause#MODEL_UNAVAILABLE} deliberately marks NOTHING, and that restraint is the
 * decision worth recording. It covers a provider outage as well as a rate limit, and the two are
 * indistinguishable in it. Treating it as exhaustion would take a perfectly good key out of rotation
 * for every provider blip — and with a small pool, one outage would rest every member at once and
 * turn a transient fault into a refusal that names a recovery time nobody can rely on. So no harness
 * reports a rate limit distinctly yet, {@code markRateLimited} has no automatic producer, and that is
 * stated here rather than left for someone to discover from an empty code path. The operator surface
 * can still rest a member by hand.
 *
 * <p>Never throws. This runs after the run's outcome and its charges are already recorded, and a
 * pool bookkeeping fault must not dead-letter a result that has been paid for.
 */
@ApplicationScoped
public class RunCredentialFeedback {

    private static final Logger LOG = Logger.getLogger(RunCredentialFeedback.class);

    @Inject
    FactoryRunProjection projection;

    @Inject
    HarnessCredentialPool pool;

    public void reactTo(RunResult result) {
        if (!(result instanceof RunResult.RunFailed failed)) {
            return;
        }
        if (RunFailureCause.of(failed.cause()) != RunFailureCause.CREDENTIAL_REJECTED) {
            return;
        }
        try {
            Optional<UUID> member = projection.harnessCredentialOf(failed.runId());
            if (member.isEmpty()) {
                // A run dispatched before the pool existed, or one whose row cannot be read. Marking
                // an arbitrary member would take a working key out of rotation on no evidence, which
                // is worse than not learning from this run.
                LOG.warnf("run %s reported its harness credential was refused, but the run names no"
                        + " pool member, so nothing was marked", failed.runId());
                return;
            }
            pool.markRejected(member.orElseThrow());
        } catch (RuntimeException e) {
            LOG.errorf(e, "run %s: its credential refusal could not be recorded against the pool (%s);"
                    + " the member stays in rotation and the next run will rediscover it",
                    failed.runId(), e.getClass().getSimpleName());
        }
    }
}
