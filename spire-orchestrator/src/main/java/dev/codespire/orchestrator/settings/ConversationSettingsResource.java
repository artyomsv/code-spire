package dev.codespire.orchestrator.settings;

import dev.codespire.contract.review.ConversationLevel;
import dev.codespire.orchestrator.provider.ConversationLevels;
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
 * Read and set the conversational-loop tuning knobs (turn cap, retry attempts, backoff) — the
 * runtime-configurable counterpart to the boot-time SPIRE_* config they replaced. Clamped on both
 * read (see {@link ConversationLevels}) and write. Separate from {@link ConversationLevelResource}
 * (which the UI still targets until its migration task); this is the backend + REST surface only.
 */
@Path("/api/settings/conversation")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConversationSettingsResource {

    @Inject
    ConversationLevels levels;

    public record ConversationSettingsView(String level, int turnCap, int maxAttempts,
                                           long backoffBaseMs, double backoffFactor) {}

    @GET
    public ConversationSettingsView get() {
        return new ConversationSettingsView(levels.globalDefault().name(), levels.turnCap(),
                levels.maxAttempts(), levels.backoffBaseMs(), levels.backoffFactor());
    }

    @PUT
    public ConversationSettingsView set(ConversationSettingsView body) {
        if (body == null || body.level() == null || body.level().isBlank()) {
            throw new BadRequestException("level is required");
        }
        ConversationLevel level;
        try {
            level = ConversationLevel.valueOf(body.level().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            throw new BadRequestException("level must be REPORT_ONLY, EXPLAIN, or INTERACTIVE");
        }
        levels.setGlobalDefault(level);
        levels.setTurnCap(body.turnCap());
        levels.setMaxAttempts(body.maxAttempts());
        levels.setBackoffBaseMs(body.backoffBaseMs());
        levels.setBackoffFactor(body.backoffFactor());
        return get();
    }
}
