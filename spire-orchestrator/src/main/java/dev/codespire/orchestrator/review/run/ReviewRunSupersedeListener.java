package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.EventEnvelope;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Reads {@code ReviewSuperseded} from {@code cs.events} on its own consumer group (harness reviews, step 4) and stops the
 * superseded head's review run. Not the timeline projector: that one is rebuildable and replays from the start, and a
 * replay must never send a cancel.
 */
@ApplicationScoped
public class ReviewRunSupersedeListener {

    @Inject
    ReviewRunCancellation cancellation;

    public void on(EventEnvelope envelope) {
        // Not yet: step 4 cancels the superseded head's run here.
    }
}
