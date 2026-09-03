package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunFailureCause;
import dev.codespire.contract.event.RunResult;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;
import java.util.UUID;

/**
 * What a run's outcome would say about the key it ran with (FR-F12).
 *
 * <p><b>Read this first: nothing in the shipped pipeline produces the cause this class waits for, so
 * the automatic half of the pool's health does not currently fire.</b> A review established it by
 * grep rather than by reading — {@link RunFailureCause#CREDENTIAL_REJECTED} appears in main sources
 * only as the enum value, an unrelated attention-row string, and the branch below. The harness tier's
 * own {@code FailureCause} has no credential value, the publisher's vocabulary has none, and nothing
 * aliases onto it. A model provider refusing a key surfaces as {@code PROVIDER_ERROR} →
 * {@code MODEL_UNAVAILABLE}, which by the rule below marks nothing.
 *
 * <p>So a dead key stays in rotation, and the pool hands it out again. That is verbatim the state
 * V52's own header calls "how a pool quietly stops rotating while looking healthy" — written in the
 * same change that shipped it. An earlier version of this javadoc and of the debt entry both said
 * this loop was closed and tested; the test injects the cause string by hand, so it proves the
 * translation and says nothing about whether anything crosses the seam.
 *
 * <p>{@code HarnessCredentialProducerGuardTest} fails the build when a producer DOES appear, so the
 * claim cannot silently become stale in the other direction either. The design for a real producer is
 * in {@code techdebt/spire-orchestrator/4-2-no-harness-reports-a-rate-limit-so-the-pool-only-heals-by-hand.md}.
 *
 * <p><b>The rule, for when a producer exists.</b> Only a refusal marks a member.
 * {@code CREDENTIAL_REJECTED} is the provider saying the key is wrong, revoked or out of credit — an
 * answer, and the same one next time, so the member leaves the pool until an operator replaces it.
 *
 * <p>{@link RunFailureCause#MODEL_UNAVAILABLE} deliberately marks NOTHING, and that restraint is the
 * decision worth recording. It covers a provider outage as well as a rate limit, and the two are
 * indistinguishable in it. Treating it as exhaustion would take a perfectly good key out of rotation
 * for every provider blip — and with a small pool, one outage would rest every member at once and
 * turn a transient fault into a refusal that names a recovery time nobody can rely on.
 *
 * <p>Never throws. This runs after the run's outcome and its charges are already recorded, and a pool
 * bookkeeping fault must not dead-letter a result that has been paid for. That ordering is
 * {@code RunResultSaga}'s and is asserted there, not merely assumed here.
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
                // Three different situations, and all three must mark nothing: a run dispatched
                // before the pool existed, a run whose dispatch was re-armed (the row nulls the
                // member then, because two commands may be live and only one can be named), and a
                // row that could not be READ -- harnessCredentialOf swallows its SQLException.
                // Marking an arbitrary member for any of them takes a working key out of rotation on
                // no evidence, which is worse than not learning from this run.
                LOG.warnf("run %s reported its harness credential was refused, but no pool member could"
                        + " be attributed to it, so nothing was marked", failed.runId());
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
