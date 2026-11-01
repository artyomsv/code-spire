package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.llm.LlmModelPricer;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * A harness review run's spend, recorded once under the review's call slot (harness reviews, step 4): the review is
 * what paid for the call, so the run plane's {@code RunCharges} skips review runs and this writes their one charge.
 */
@ApplicationScoped
public class ReviewRunCharges {

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
        throw new UnsupportedOperationException("review run charges are not recorded yet: " + result.runId());
    }
}
