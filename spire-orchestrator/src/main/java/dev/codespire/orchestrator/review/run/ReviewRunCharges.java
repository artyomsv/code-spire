package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.work.PayWith;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.factory.RunCharges;
import dev.codespire.orchestrator.factory.RunTokenUsage;
import dev.codespire.orchestrator.llm.CallRefs;
import dev.codespire.orchestrator.llm.ChargeCall;
import dev.codespire.orchestrator.llm.ChargeKind;
import dev.codespire.orchestrator.llm.ChargeLine;
import dev.codespire.orchestrator.llm.LlmModelPricer;
import dev.codespire.orchestrator.llm.ReviewSpend;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.UUID;

/**
 * A harness review run's spend, recorded once under the review's call slot (harness reviews, step 4): the review is
 * what paid for the call, so the run plane's {@code RunCharges} skips review runs and this writes their one charge.
 *
 * <p>The same writer, the same usage reading and the same "nothing was bought" rule as the run plane, so the two
 * charges cannot drift; only the identity differs. The ledger's {@code UNIQUE (call_ref, token_type)} makes a
 * redelivered result write nothing new.
 */
@ApplicationScoped
public class ReviewRunCharges {

    private static final Logger LOG = Logger.getLogger(ReviewRunCharges.class);

    /** The model recorded when the run's row cannot be read, for the reason {@code RunCharges} gives. */
    private static final String UNRECORDED_MODEL = "UNRECORDED";

    @Inject
    ReviewProjection ledger;

    @Inject
    FactoryRunProjection runs;

    @Inject
    LlmModelPricer pricer;

    /** The same ceiling the run plane prices under; see {@code RunCharges.maxReportedTokens}. */
    @ConfigProperty(name = "spire.run.max-reported-tokens", defaultValue = "-1")
    long maxReportedTokens;

    public void record(RunResult result, ReviewRunBinding binding) {
        if (result instanceof RunResult.RunStarted || RunCharges.nothingWasBought(result)) {
            return;
        }
        String runId = result.runId();
        try {
            String model = runs.modelOf(runId).orElse(UNRECORDED_MODEL);
            ModelUsage usage = RunTokenUsage.of(result, model, maxReportedTokens);
            // Priced by how the run PAID: a seat's tokens cost nothing per token, and keep their real counts.
            boolean seat = runs.paidByOf(runId).filter(PayWith.SUBSCRIPTION::equals).isPresent();
            List<ChargeLine> lines = seat ? pricer.priceUnmetered(usage) : pricer.priceCall(model, usage);
            ChargeKind kind = ChargeKind.valueOf(binding.kind().name());
            String credentialRef = runs.harnessCredentialOf(runId).map(UUID::toString).orElse(null);
            ledger.recordCharges(ChargeCall.forReview(new ReviewSpend(binding.reviewId(),
                    CallRefs.of(binding.reviewId(), binding.callSlot(), kind), kind, credentialRef), model, lines));
        } catch (RuntimeException e) {
            // The projection has already written the run's terminal status. Throwing would dead-letter the result and
            // replay it, so a ledger outage would become a redelivery loop; the spend is lost and said out loud.
            LOG.errorf(e, "review run %s: its charge for %s could not be recorded, so this spend is missing from the "
                    + "deployment's rolling window", runId, binding.reviewId());
        }
    }
}
