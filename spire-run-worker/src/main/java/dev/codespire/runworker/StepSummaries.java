package dev.codespire.runworker;

import dev.codespire.contract.work.StepSummary;
import dev.codespire.secrets.SecretScrub;

import java.util.Optional;

/** RED: nothing is read yet. */
final class StepSummaries {

    private StepSummaries() {
    }

    static StepSummary from(PublisherOutcome outcome, SecretScrub scrub) {
        throw new UnsupportedOperationException("RED");
    }

    static Optional<StepSummary> parse(String json, SecretScrub scrub) {
        throw new UnsupportedOperationException("RED");
    }
}
