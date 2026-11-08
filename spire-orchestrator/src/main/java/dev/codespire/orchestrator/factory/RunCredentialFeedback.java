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
 * <p><b>One producer exists, and only on a proxied unit.</b> Since harness reviews step 3 the run
 * worker's {@code ModelProxyResults} reports {@link RunFailureCause#CREDENTIAL_REJECTED} when the
 * unit's model proxy saw the vendor answer 401 on a model call path ({@code /v1/responses},
 * {@code /backend-api/codex/responses}) and the agent then failed. A 401 elsewhere and every 403 are
 * deliberately not a refusal: a 403 can be a challenge or an entitlement answer, and a mark keeps a
 * member out until an operator replaces it. {@code RunResultSagaTest} proves a review run's refusal
 * reaches the pool member its {@code factory_run} row names.
 *
 * <p>An unproxied unit (a factory build while {@code spire.run.credential-proxy.mode} is REVIEWS, or
 * any unit with the mode OFF or egress off) still has no producer: there a provider refusing a key
 * surfaces as the harness's own failure ({@code HARNESS_EXIT_NONZERO} for Codex, or
 * {@code PROVIDER_ERROR} → {@code MODEL_UNAVAILABLE}), which by the rule below marks nothing, and the
 * dead key stays in rotation. See
 * {@code techdebt/spire-orchestrator/4-2-no-harness-reports-a-rate-limit-so-the-pool-only-heals-by-hand.md}.
 *
 * <p><b>The rule.</b> Only a refusal marks a member.
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
