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
 * <p>{@code ReviewSuperseded} names the OLD commit, the head now replaced, so that is the head whose run is cancelled;
 * the new head's run, dispatched after it, reads another commit and is left alone.
 */
@ApplicationScoped
public class ReviewRunSupersedeListener {

    @Inject
    ReviewRunCancellation cancellation;

    @Incoming("review-run-supersede-in")
    @Blocking
    public void on(EventEnvelope envelope) {
        if (envelope == null) {
            return; // undeserializable envelope already logged by the deserializer
        }
        if (envelope.payload() instanceof DomainEvent.ReviewSuperseded superseded) {
            cancellation.supersede(envelope.streamId(), superseded.commit());
        }
    }
}
