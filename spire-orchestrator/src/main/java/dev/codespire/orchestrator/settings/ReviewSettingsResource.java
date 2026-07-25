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

    public record ReviewSettingsView(int maxAttempts) {
    }

    @GET
    public ReviewSettingsView get() {
        return new ReviewSettingsView(policy.maxAttempts());
    }

    @PUT
    public ReviewSettingsView set(ReviewSettingsView body) {
        if (body == null) {
            throw new BadRequestException("maxAttempts is required");
        }
        if (body.maxAttempts() < ReviewPolicy.MIN_ATTEMPTS || body.maxAttempts() > ReviewPolicy.MAX_ATTEMPTS) {
            throw new BadRequestException("maxAttempts must be between " + ReviewPolicy.MIN_ATTEMPTS
                    + " and " + ReviewPolicy.MAX_ATTEMPTS);
        }
        policy.setMaxAttempts(body.maxAttempts());
        return new ReviewSettingsView(policy.maxAttempts());
    }
}
