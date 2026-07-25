package dev.codespire.orchestrator.settings;

import dev.codespire.orchestrator.policy.ReviewPolicy;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

/**
 * Read and change the review pipeline's own tuning (spire-ui Settings), kept apart from
 * {@code /api/settings/conversation} because the two retry budgets are genuinely different: a review
 * that exhausts its attempts ends as a failed review carrying the provider's error, while a follow-up
 * answer dead-letters for replay. Presenting one number for both led an operator to set 5 and see a
 * review stop after 3.
 *
 * <p>The review MODE lives on its own endpoint ({@code /api/settings/review-mode}) because the sidebar
 * toggle already owns it.
 */
@Path("/api/settings/review")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReviewSettingsResource {

    @Inject
    ReviewPolicy policy;

    public record ReviewSettingsView(int maxAttempts, long backoffBaseMs, double backoffFactor) {
    }

    @GET
    public ReviewSettingsView get() {
        return current();
    }

    @PUT
    public ReviewSettingsView set(ReviewSettingsView body) {
        if (body == null) {
            throw new BadRequestException("maxAttempts, backoffBaseMs and backoffFactor are required");
        }
        if (body.maxAttempts() < ReviewPolicy.MIN_ATTEMPTS || body.maxAttempts() > ReviewPolicy.MAX_ATTEMPTS) {
            throw new BadRequestException("maxAttempts must be between " + ReviewPolicy.MIN_ATTEMPTS
                    + " and " + ReviewPolicy.MAX_ATTEMPTS);
        }
        if (body.backoffBaseMs() < 0 || body.backoffBaseMs() > ReviewPolicy.MAX_BACKOFF_MS) {
            throw new BadRequestException("backoffBaseMs must be between 0 and " + ReviewPolicy.MAX_BACKOFF_MS);
        }
        if (body.backoffFactor() < ReviewPolicy.MIN_BACKOFF_FACTOR
                || body.backoffFactor() > ReviewPolicy.MAX_BACKOFF_FACTOR) {
            throw new BadRequestException("backoffFactor must be between " + ReviewPolicy.MIN_BACKOFF_FACTOR
                    + " and " + ReviewPolicy.MAX_BACKOFF_FACTOR);
        }
        policy.setMaxAttempts(body.maxAttempts());
        policy.setBackoff(body.backoffBaseMs(), body.backoffFactor());
        return current();
    }

    private ReviewSettingsView current() {
        return new ReviewSettingsView(policy.maxAttempts(), policy.backoffBaseMs(), policy.backoffFactor());
    }
}
