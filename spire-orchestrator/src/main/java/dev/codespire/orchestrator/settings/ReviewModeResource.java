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

import java.util.Locale;

/**
 * Read and toggle the global review mode (spire-ui Settings). The PUT flips
 * observe &lt;-&gt; active and is picked up by the next PR event — no restart.
 */
@Path("/api/settings/review-mode")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReviewModeResource {

    @Inject
    ReviewPolicy policy;

    public record ReviewModeView(String mode) {}

    @GET
    public ReviewModeView get() {
        return new ReviewModeView(policy.currentMode());
    }

    @PUT
    public ReviewModeView set(ReviewModeView body) {
        if (body == null || body.mode() == null || body.mode().isBlank()) {
            throw new BadRequestException("mode is required");
        }
        String mode = body.mode().trim().toLowerCase(Locale.ROOT);
        if (!ReviewPolicy.OBSERVE.equals(mode) && !ReviewPolicy.ACTIVE.equals(mode)) {
            throw new BadRequestException("mode must be 'observe' or 'active'");
        }
        policy.setMode(mode);
        return new ReviewModeView(policy.currentMode());
    }
}
