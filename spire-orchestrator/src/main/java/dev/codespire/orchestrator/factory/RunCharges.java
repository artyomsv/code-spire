package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunFailureCause;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.orchestrator.llm.CallRefs;
import dev.codespire.orchestrator.llm.ChargeCall;
import dev.codespire.orchestrator.llm.ChargeLine;
import dev.codespire.orchestrator.llm.LlmModelPricer;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * A run's spend, written to the ledger the spend cap reads.
 *
 * <p>The risk this closes is not a missing number on a page. Until a run wrote here, a deployment
 * could run the factory all day, spend real money, and the cap that exists to stop it never moved —
 * the same shape as the LLM circuit breaker that once recorded a failed future as a success: a
 * control that installs cleanly and never fires. {@code SpendWindow} reads {@code llm_charge} with
 * no subject filter, so the cap starts working the moment these rows exist.
 *
 * <p><b>One writer.</b> This goes through the same {@code recordCharges} the review path uses rather
 * than growing an INSERT of its own: two writers to one money table are free to drift, and drift in a
 * money path is invisible until it fails to fire — the reason {@code SpendGate} is shared too.
 */
@ApplicationScoped
public class RunCharges {

    private static final Logger LOG = Logger.getLogger(RunCharges.class);

    /**
     * The model name recorded when the run's row cannot be read.
     *
     * <p>{@code llm_charge.model} is NOT NULL, which made a missing model look like a reason to skip
     * the write. Skipping loses the spend; saying so does not, and a name that reads as a fault is
     * what makes the row findable afterwards. It is not a catalogued name, so the line prices as
     * UNKNOWN rather than free — and because the ledger discards a duplicate key, a transient fault
     * reading the model is permanent for that run.
     */
    static final String UNRECORDED_MODEL = "UNRECORDED";

    /**
     * The call's sequence within the run.
     *
     * <p>A constant, because a run IS one charge: the agent makes many model calls inside its
     * sandbox and the harness reports only its own totals, so a finer grain would be invented rather
     * than measured. The attempt lives in the run id, so a genuine second run already keys
     * differently while a redelivery of the same run reproduces this key exactly — which is what the
     * ledger's {@code UNIQUE (call_ref, token_type)} then discards.
     */
    private static final String AGENT_CALL = "agent";

    @Inject
    ReviewProjection ledger;

    @Inject
    FactoryRunProjection runs;

    @Inject
    LlmModelPricer pricer;

    /**
     * The largest self-reported usage this deployment will PRICE for one run.
     *
     * <p>Any non-positive value means unlimited, the same posture every ADR-025 cap takes. See
     * {@code RunTokenUsage.of} for what a report above it does, why a default would be a number
     * this code invented about somebody else's models, and why zero cannot mean "refuse
     * everything".
     */
    @ConfigProperty(name = "spire.run.max-reported-tokens", defaultValue = "-1")
    long maxReportedTokens;

    /**
     * Record what this result says the run spent.
     *
     * <p>A failure is charged as readily as a success. An agent can work for an hour and then have
     * its push rejected, and those tokens were bought — losing that spend leaves the cap blind to
     * exactly the runs most likely to be run again, so the deployment is under-counted precisely
     * where it is about to be charged a second time.
     */
    public void record(RunResult result) {
        if (result instanceof RunResult.RunStarted) {
            return; // nothing has been spent yet, and pricing it would invent the usage
        }
        if (nothingWasBought(result)) {
            return;
        }
        String runId = result.runId();
        String model = runs.modelOf(runId).orElse(UNRECORDED_MODEL);
        try {
            // Pricing sits INSIDE the guard, not one line above it. LlmModelPricer defends its own
            // SQL and arithmetic, but a stored pricing_mode the enum does not recognise throws
            // IllegalArgumentException out of valueOf — which would reach the messaging layer and
            // produce exactly the redelivery loop the comment below says this catch prevents.
            ModelUsage usage = RunTokenUsage.of(result, model, maxReportedTokens);
            List<ChargeLine> lines = pricer.priceCall(model, usage);
            // Which key paid, read from the run's own row like the model beside it. Empty for a run
            // dispatched before the pool existed; the column is nullable for exactly that.
            String credentialRef = runs.harnessCredentialOf(runId).map(UUID::toString).orElse(null);
            ledger.recordCharges(ChargeCall.forRun(runId, CallRefs.forRun(runId, AGENT_CALL), model,
                    lines, credentialRef));
        } catch (RuntimeException e) {
            // The projection has already written this run's terminal status by the time we get here.
            // Throwing would dead-letter the result and replay it, re-applying the projection — so a
            // ledger outage would turn every finished run into a redelivery loop. The spend is lost
            // and said out loud, which is the lesser of the two and the only one an operator can act on.
            LOG.errorf(e, "run %s: its charges could not be recorded, so this spend is missing from the "
                    + "deployment's rolling window; the run's own outcome is unaffected", runId);
        }
    }

    /**
     * Whether this result names a failure raised before the agent could buy anything.
     *
     * <p>Skipping is as dangerous as charging, in the other direction, so the test is narrow. A
     * zero-token row is not a harmless extra: the deployment-wide cap counts
     * {@code COUNT(DISTINCT call_ref)} alongside the money, so a daemon outage failing every dispatch
     * in seconds would spend the whole call budget on runs that bought nothing — and that budget
     * gates the review pipeline too. On an UNMETERED deployment the call axis is the ONLY axis, so it
     * would be the entire cap.
     *
     * <p><b>The discriminator is the cause, not the usage.</b> "Usage unknown" cannot serve: a
     * post-agent failure with unmeasured usage MUST still be charged, which is the whole reason
     * {@code RunFailed} carries usage at all. {@link RunFailureCause#agentMayHaveSpent()} answers the
     * actual question and defaults to charging, so only a cause that provably precedes the agent is
     * skipped. A failure that DID report usage is charged whatever its cause says.
     */
    private static boolean nothingWasBought(RunResult result) {
        return result instanceof RunResult.RunFailed failed
                && !failed.usageIsKnown()
                && !RunFailureCause.of(failed.cause()).agentMayHaveSpent();
    }
}
