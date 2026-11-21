package dev.codespire.orchestrator.settings;

import dev.codespire.orchestrator.llm.LlmDefaultModel;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * The global default review model (spire-ui Settings → LLM), beside the default provider. The PUT changes only the
 * model, for the current default provider; changing the provider goes through {@code PUT /api/llm-providers/{id}/default},
 * which writes both.
 */
@Path("/api/settings/llm-default-model")
// Admin-only, as GET /api/repositories is: the repository "Default" label reads this.
@RolesAllowed("spire-admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class LlmDefaultModelResource {

    @Inject
    LlmDefaultModel defaultModel;

    /** The enabled default provider's id and the default model; either may be null. */
    public record LlmDefaultModelView(String provider, String model) {
    }

    /** Body of the PUT: a model name, or null to clear it. */
    public record LlmDefaultModelInput(String model) {
    }

    @GET
    public LlmDefaultModelView get() {
        // One statement, so the provider and the model come from one committed state.
        LlmDefaultModel.DefaultPair pair = defaultModel.view();
        return new LlmDefaultModelView(pair.provider(), pair.model());
    }

    @PUT
    @RolesAllowed("spire-admin")
    public LlmDefaultModelView set(LlmDefaultModelInput body) {
        String model = body == null || body.model() == null || body.model().isBlank() ? null : body.model().strip();
        try {
            defaultModel.set(model);
        } catch (LlmDefaultModel.ChoiceRefused refused) {
            throw new BadRequestException(Response.status(Response.Status.BAD_REQUEST)
                    .type(MediaType.TEXT_PLAIN).entity(refused.getMessage()).build());
        } catch (LlmDefaultModel.NoDefaultProvider none) {
            throw new ClientErrorException(Response.status(Response.Status.CONFLICT)
                    .type(MediaType.TEXT_PLAIN).entity(none.getMessage()).build());
        }
        return get();
    }
}
