package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.HarnessImageResult;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.util.concurrent.CompletionStage;

/** What the run worker reported an agent image declares, handed to {@link HarnessCatalogues}. */
@ApplicationScoped
public class HarnessImageResults {

    private static final Logger LOG = Logger.getLogger(HarnessImageResults.class);

    @Inject HarnessCatalogues catalogues;

    @Incoming("harness-image-results-in")
    @Blocking(ordered = false)
    public CompletionStage<Void> onResult(Message<HarnessImageResult> message) {
        if (message.getPayload() instanceof HarnessImageResult.Described described) {
            try {
                catalogues.record(described);
            } catch (RuntimeException failure) {
                // Nack, so the dead-letter queue sees it. The next scheduled ask would repair the cache
                // anyway, but a failure nobody can see is how a stale list goes unnoticed for a week.
                LOG.errorf(failure, "the model catalogue for %s could not be recorded", described.harness());
                return message.nack(failure);
            }
        }
        return message.ack();
    }
}
