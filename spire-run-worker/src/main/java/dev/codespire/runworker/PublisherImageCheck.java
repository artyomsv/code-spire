package dev.codespire.runworker;

import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Refuses to start without a publisher image. The value has no production default and is read by
 * a lazily created bean, so a deployment missing it started clean and failed on its first run —
 * as {@code BAD_COMMAND}, not retryable, burning the subject. Its sibling, the wall clock, is
 * guarded at startup by {@link RunAckBudget}; this makes the pair symmetrical.
 */
@ApplicationScoped
public class PublisherImageCheck {

    static final String PROPERTY = "spire.run.publisher-image";

    @ConfigProperty(name = PROPERTY)
    Optional<String> publisherImage;

    void check(@Observes StartupEvent event) {
        verify(publisherImage);
    }

    static void verify(Optional<String> publisherImage) {
        if (publisherImage.isEmpty() || publisherImage.orElseThrow().isBlank()) {
            throw new IllegalStateException(PROPERTY + " (SPIRE_PUBLISHER_IMAGE) is required: it names the "
                    + "image every run unit's init and publisher containers run, and there is no safe default.");
        }
    }
}
