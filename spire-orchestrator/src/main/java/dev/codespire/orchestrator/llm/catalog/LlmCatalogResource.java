package dev.codespire.orchestrator.llm.catalog;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.MediaType;

import java.util.List;
import java.util.Map;

/** Settings → LLM → Models: the catalog in use, and "Reload now". */
@Path("/api/llm-catalog")
@RolesAllowed("spire-admin")
@Produces(MediaType.APPLICATION_JSON)
public class LlmCatalogResource {

    private static final String BUNDLED = "bundled";

    @Inject
    LlmModelCatalog catalog;

    @Inject
    LlmCatalogStateRepository states;

    @Inject
    LlmCatalogRefresh refresh;

    @Inject
    ObjectMapper mapper;

    @GET
    public LlmCatalogView view() {
        List<LlmModelCatalog.CatalogRow> rows;
        try {
            rows = catalog.catalogRows();
        } catch (LlmModelCatalog.CatalogueReadFailed unreadable) {
            throw new ServiceUnavailableException("The model tables could not be read; try again");
        }
        String sha = catalog.snapshot().sha256();
        if (catalog.remoteLoadingOff()) {
            return new LlmCatalogView(null, BUNDLED, sha, null, null, null, null, null, rows);
        }
        LlmCatalogState state = states.read();
        return new LlmCatalogView(catalog.url(), sourceOf(state, sha), sha, state.loadedAt(), state.lastCheckedAt(),
                state.lastSuccessAt(), state.lastError(), lastChange(state.lastChangeJson()), rows);
    }

    @POST
    @Path("/refresh")
    public Map<String, LlmCatalogRefresh.RefreshOutcome> refresh() {
        return Map.of("outcome", refresh.refreshNow());
    }

    /** The stored row's source, when the catalog in use is the stored one; else the bundled copy is in use. */
    private static String sourceOf(LlmCatalogState state, String sha) {
        return sha != null && sha.equals(state.sha256()) ? state.source() : BUNDLED;
    }

    private JsonNode lastChange(String json) {
        if (json == null) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (JsonProcessingException corrupt) {
            return null;
        }
    }
}
