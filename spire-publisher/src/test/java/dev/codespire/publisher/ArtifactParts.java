package dev.codespire.publisher;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reads the artifact back out of a publisher's output the way the run worker must (harness reviews, step 4): the
 * {@code artifact_part} lines, each under the run worker's line cap and each at most 32 KiB of text, joined by index.
 */
final class ArtifactParts {

    /** DockerRunRuntime.MAX_LINE_CHARS: the longest outcome line the run worker receives whole. */
    private static final int MAX_LINE_CHARS = 64 * 1024;

    private static final int MAX_PART_BYTES = 32 * 1024;

    private static final ObjectMapper JSON = new ObjectMapper();

    private ArtifactParts() {
    }

    /** The joined text; fails the test on a line over the cap, a part over its bound, or a gap in the indices. */
    static String join(String output) {
        List<JsonNode> parts = new ArrayList<>();
        for (String line : output.split("\n")) {
            JsonNode node = parse(line.strip());
            if (node != null && "artifact_part".equals(node.path("event").asText())) {
                assertTrue(line.strip().length() <= MAX_LINE_CHARS, "a part line of " + line.length() + " characters");
                parts.add(node);
            }
        }
        assertFalse(parts.isEmpty(), "no artifact_part line in: " + output.substring(0, Math.min(200, output.length())));
        StringBuilder text = new StringBuilder();
        for (int index = 0; index < parts.size(); index++) {
            JsonNode part = parts.get(index);
            assertEquals(index, part.path("index").asInt(-1), "parts are written in order, numbered from 0");
            assertEquals(parts.size(), part.path("count").asInt(-1), "every part names the count");
            String piece = part.path("text").asText();
            assertTrue(piece.getBytes(StandardCharsets.UTF_8).length <= MAX_PART_BYTES, "a part over 32 KiB");
            text.append(piece);
        }
        return text.toString();
    }

    private static JsonNode parse(String line) {
        try {
            return line.isEmpty() ? null : JSON.readTree(line);
        } catch (JsonProcessingException notJson) {
            return null;
        }
    }
}
