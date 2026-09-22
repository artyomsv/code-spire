package dev.codespire.runworker;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.HarnessImageResult;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * Reads the model catalogue an agent image declares in its labels (M3.5 part M).
 *
 * <p>The label is written by {@code deploy/agent/build-codex.sh}: base64 of a JSON array whose objects
 * use short keys — {@code s} slug, {@code n} display name, {@code d} default level, {@code e} levels,
 * {@code v} visibility, {@code p} priority — because the whole value rides in image metadata and every
 * byte of it is copied into every registry manifest.
 *
 * <p>Pure: no daemon, no broker, so every malformed case can be tested directly.
 */
final class ModelCatalogueLabel {

    /** The label the reference image carries. Declared in the image contract, clause {@code models}. */
    static final String LABEL = "dev.codespire.agent.models";

    /** What the vendor marks a model it wants offered. Anything else is present but not offered. */
    private static final String SHOWN = "list";

    private ModelCatalogueLabel() {
    }

    /** A status and, only when it is OK, the models. */
    record Read(HarnessImageResult.Status status, List<HarnessImageResult.Model> models) {
    }

    static Read of(Map<String, String> labels, ObjectMapper mapper) {
        String value = labels.get(LABEL);
        // Blank counts as absent: a plain `docker build` of the Dockerfile sets the label from an empty
        // build argument, so the key exists with nothing in it. That is "no catalogue", not "broken".
        if (value == null || value.isBlank()) return new Read(HarnessImageResult.Status.NO_CATALOGUE, List.of());
        try {
            JsonNode array = mapper.readTree(new String(Base64.getDecoder().decode(value.trim()), StandardCharsets.UTF_8));
            if (!array.isArray() || array.isEmpty()) return unreadable();
            List<HarnessImageResult.Model> models = new ArrayList<>();
            for (JsonNode entry : array) {
                String slug = entry.path("s").asText("");
                if (slug.isBlank()) return unreadable();
                List<String> efforts = new ArrayList<>();
                entry.path("e").forEach(level -> efforts.add(level.asText()));
                models.add(new HarnessImageResult.Model(slug, entry.path("n").asText(slug),
                        entry.path("d").asText(""), efforts, SHOWN.equals(entry.path("v").asText(SHOWN)),
                        entry.path("p").asInt(Integer.MAX_VALUE)));
            }
            return new Read(HarnessImageResult.Status.OK, List.copyOf(models));
        } catch (IllegalArgumentException | java.io.IOException notACatalogue) {
            // One bad entry makes the WHOLE label unreadable rather than yielding the entries that
            // parsed: a list with a silent hole in it looks exactly like a complete list.
            return unreadable();
        }
    }

    private static Read unreadable() {
        return new Read(HarnessImageResult.Status.UNREADABLE, List.of());
    }
}
