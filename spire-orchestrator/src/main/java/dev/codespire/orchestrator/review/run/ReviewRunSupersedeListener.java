package dev.codespire.orchestrator.review.run;

import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.EventEnvelope;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;

/**
 * Reads {@code ReviewSuperseded} from {@code cs.events} on its own consumer group (harness reviews, step 4) and stops the
 * superseded head's review run. Not the timeline projector: that one is rebuildable and replays from the start, and a
 * replay must never send a cancel.
 *
 * <p>A supersede ends a review ROUND, not a commit: a forced re-run supersedes round N and requests round N+1 on the
 * same head. So the round cancelled is the one the supersede ended, counted from the review's stream at the supersede's
 * own position, and the next round's runs, on whatever head, are left alone (review of PR #367).
 */
@ApplicationScoped
public class ReviewRunSupersedeListener {

    @Inject
    ReviewRunCancellation cancellation;

    @Inject
    dev.codespire.orchestrator.llm.ReviewRuns rounds;

    @Incoming("review-run-supersede-in")
    @Blocking
    public void on(EventEnvelope envelope) {
        if (envelope == null) {
            return; // undeserializable envelope already logged by the deserializer
        }
        if (!(envelope.payload() instanceof DomainEvent.ReviewSuperseded)) {
            return;
        }
        // The round this supersede ended, read at its own position in the stream: the request it was emitted with is
        // after it, so a forced re-run on the same head is never the round cancelled.
        int round = rounds.roundSupersededAt(envelope.streamId(), envelope.sequence());
        if (round == dev.codespire.orchestrator.llm.ReviewRuns.UNKNOWN_RUN) {
            return; // ReviewRuns logged why; a guessed round could stop the current one
        }
        cancellation.supersede(envelope.streamId(), round);
    }
}
