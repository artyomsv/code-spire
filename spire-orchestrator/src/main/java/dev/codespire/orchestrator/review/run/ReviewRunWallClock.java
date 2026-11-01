package dev.codespire.orchestrator.review.run;

import dev.codespire.orchestrator.factory.FactoryConfig;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;

/**
 * Refuses to start when a review run's wall clock exceeds a build's (harness reviews, step 4). The run-worker refuses a
 * unit above its own bound, so a review allowed longer here would be paid for, dispatched and refused there.
 */
@ApplicationScoped
public class ReviewRunWallClock {

    @Inject
    FactoryConfig config;

    void onStart(@Observes StartupEvent event) {
        check(config.reviewWallClockSeconds(), config.wallClockSeconds());
    }

    /** @throws IllegalStateException when {@code reviewSeconds} is above {@code buildSeconds} */
    static void check(long reviewSeconds, long buildSeconds) {
        // Not yet: step 4 refuses here.
    }
}
