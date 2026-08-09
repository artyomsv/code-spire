package dev.codespire.orchestrator.settings;

import dev.codespire.orchestrator.caps.CapPolicy;
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

import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * Read and set the fleet spend limits (spire-ui Settings -> General, Limits section) backed by
 * {@link CapPolicy}. Every field is optional: {@code null} means unlimited (the four caps) or "use
 * the default window" (the window), and a {@code null} on write clears a previously stored limit back
 * to that state -- never {@code 0}, which is precisely how ADR-023's "unknown became zero" bug
 * entered and here would turn "no cap" into "cap of zero", refusing every review.
 */
@Path("/api/settings/caps")
@RolesAllowed("spire-admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class CapSettingsResource {

    @Inject
    AppSettingRepository settings;

    @Inject
    CapPolicy policy;

    public record CapSettingsView(Integer maxChangedFiles, Long maxDiffBytes, Long spendCapMillicents,
                                   Integer callCap, Long windowMinutes) {
    }

    @GET
    public CapSettingsView get() {
        return current();
    }

    @PUT
    @RolesAllowed("spire-admin")
    public CapSettingsView set(CapSettingsView body) {
        if (body == null) {
            throw badRequest("maxChangedFiles, maxDiffBytes, spendCapMillicents, callCap and "
                    + "windowMinutes are required (send null for any that should be unlimited)");
        }
        requirePositiveOrNull(body.maxChangedFiles(), "maxChangedFiles");
        requirePositiveOrNull(body.maxDiffBytes(), "maxDiffBytes");
        requirePositiveOrNull(body.spendCapMillicents(), "spendCapMillicents");
        requirePositiveOrNull(body.callCap(), "callCap");
        requirePositiveOrNull(body.windowMinutes(), "windowMinutes");

        store(CapPolicy.KEY_MAX_CHANGED_FILES, body.maxChangedFiles());
        store(CapPolicy.KEY_MAX_DIFF_BYTES, body.maxDiffBytes());
        store(CapPolicy.KEY_SPEND_CAP, body.spendCapMillicents());
        store(CapPolicy.KEY_CALLS, body.callCap());
        store(CapPolicy.KEY_WINDOW_MINUTES, body.windowMinutes());
        return current();
    }

    private CapSettingsView current() {
        return new CapSettingsView(
                nullableInt(policy.maxChangedFiles()),
                nullableLong(policy.maxDiffBytes()),
                nullableLong(policy.spendCapMillicents()),
                nullableInt(policy.callCap()),
                policy.window().toMinutes());
    }

    // A blank stored value parses as unset (see CapPolicy), so writing "" is how a null in the
    // request body clears a previously stored limit -- there is no delete() on AppSettingRepository.
    private void store(String key, Number value) {
        settings.set(key, value == null ? "" : value.toString());
    }

    private static void requirePositiveOrNull(Number value, String field) {
        if (value != null && value.longValue() <= 0) {
            throw badRequest(field + " must be positive, or omitted/null for unlimited");
        }
    }

    private static Integer nullableInt(OptionalInt value) {
        return value.isPresent() ? value.getAsInt() : null;
    }

    private static Long nullableLong(OptionalLong value) {
        return value.isPresent() ? value.getAsLong() : null;
    }

    // BadRequestException(String) only sets the exception's own message, not the HTTP response
    // body -- the entity must be set explicitly so callers see the actionable error.
    private static BadRequestException badRequest(String message) {
        return new BadRequestException(Response.status(Response.Status.BAD_REQUEST).entity(message).build());
    }
}
