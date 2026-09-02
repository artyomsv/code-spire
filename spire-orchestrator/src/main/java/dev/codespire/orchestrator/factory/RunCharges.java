package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.orchestrator.llm.CallRefs;
import dev.codespire.orchestrator.llm.ChargeCall;
import dev.codespire.orchestrator.llm.ChargeLine;
import dev.codespire.orchestrator.llm.LlmModelPricer;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;

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
     * what makes the row findable afterwards.
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
        String runId = result.runId();
        String model = runs.modelOf(runId).orElse(UNRECORDED_MODEL);
        ModelUsage usage = RunTokenUsage.of(result, model);
        List<ChargeLine> lines = pricer.priceCall(model, usage);
        try {
            ledger.recordCharges(ChargeCall.forRun(runId, CallRefs.forRun(runId, AGENT_CALL), model, lines));
        } catch (RuntimeException e) {
            // The projection has already written this run's terminal status by the time we get here.
            // Throwing would dead-letter the result and replay it, re-applying the projection — so a
            // ledger outage would turn every finished run into a redelivery loop. The spend is lost
            // and said out loud, which is the lesser of the two and the only one an operator can act on.
            LOG.errorf(e, "run %s: its charges could not be recorded, so this spend is missing from the "
                    + "deployment's rolling window; the run's own outcome is unaffected", runId);
        }
    }
}
