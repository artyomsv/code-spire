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
        if (reviewSeconds > buildSeconds) {
            throw new IllegalStateException("spire.factory.review-wall-clock-seconds (" + reviewSeconds
                    + ") is above spire.factory.wall-clock-seconds (" + buildSeconds + "); lower the review bound "
                    + "(SPIRE_FACTORY_REVIEW_WALL_CLOCK_SECONDS) or raise the build bound, or every review run is "
                    + "refused by the run-worker after it was paid for");
        }
    }
}
