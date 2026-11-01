package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.review.ReviewRunBinding;

/**
 * Where a harness review run's result goes once it is projected and charged (harness reviews, step 4). A port: step 4
 * records the outcome and nothing else, and step 5 turns the artifact into findings behind the same call.
 */
public interface ReviewRunResults {

    void accept(RunResult result, ReviewRunBinding binding);
}
