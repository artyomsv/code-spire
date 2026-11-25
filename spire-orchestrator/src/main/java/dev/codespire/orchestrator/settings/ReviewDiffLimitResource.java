package dev.codespire.orchestrator.settings;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.math.BigDecimal;

/**
 * The Review diff limit (spire-ui Settings → General), on its own endpoint as {@code llm.default-model} is: one
 * nullable number, where {@code null} means "as much as the model's window allows".
 */
@Path("/api/settings/review-diff-limit")
@RolesAllowed("spire-admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReviewDiffLimitResource {

    @Inject
    ReviewDiffLimit limit;

    /** The stored limit in tokens, or null when unset. */
    public record ReviewDiffLimitView(Integer limitTokens) {
    }

    /**
     * Body of the PUT: a whole number of tokens, or null to clear it. A decimal rather than an integer type, because
     * Jackson would otherwise truncate {@code 30000.5} to a limit nobody typed.
     */
    public record ReviewDiffLimitInput(BigDecimal limitTokens) {
    }

    @GET
    public ReviewDiffLimitView get() {
        return new ReviewDiffLimitView(limit.current().orElse(null));
    }

    @PUT
    @RolesAllowed("spire-admin")
    public ReviewDiffLimitView set(ReviewDiffLimitInput body) {
        BigDecimal requested = body == null ? null : body.limitTokens();
        if (requested == null) {
            limit.set(null);
            return get();
        }
        if (!isWholeNumberInRange(requested)) {
            throw new BadRequestException(Response.status(Response.Status.BAD_REQUEST).type(MediaType.TEXT_PLAIN)
                    .entity("Review diff limit must be a whole number of tokens from " + ReviewDiffLimit.MIN_TOKENS
                            + " to " + ReviewDiffLimit.MAX_TOKENS + ", or empty for as much as the model allows.")
                    .build());
        }
        limit.set(requested.intValueExact());
        return get();
    }

    private static boolean isWholeNumberInRange(BigDecimal value) {
        if (value.signum() != 0 && value.stripTrailingZeros().scale() > 0) {
            return false;
        }
        return value.compareTo(BigDecimal.valueOf(ReviewDiffLimit.MIN_TOKENS)) >= 0
                && value.compareTo(BigDecimal.valueOf(ReviewDiffLimit.MAX_TOKENS)) <= 0;
    }
}
