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

/** Read and set the GLOBAL default conversation level (spire-ui Settings). Per-provider overrides live on the provider. */
@Path("/api/settings/conversation-level")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ConversationLevelResource {

    @Inject
    ConversationLevels levels;

    public record ConversationLevelView(String level) {}

    @GET
    public ConversationLevelView get() {
        return new ConversationLevelView(levels.globalDefault().name());
    }

    @PUT
    public ConversationLevelView set(ConversationLevelView body) {
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
        return get();
    }
}
