package dev.codespire.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lenient parser for the model's JSON review output — the pragmatic port of
 * pr-agent's battle-earned "LLMs emit almost-valid output" hardening
 * (try_fix_yaml, adapted to our JSON contract): strips fences, extracts the
 * outermost JSON object, tolerates trailing commas, and degrades gracefully
 * (raw text becomes the summary, zero findings) instead of failing a review.
 */
public final class FindingsParser {

    private static final ObjectMapper LENIENT = JsonMapper.builder()
            .enable(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .build();

    private FindingsParser() {
    }

    public static ReviewResult parse(String modelOutput, ModelUsage usage) {
        String json = extractJson(modelOutput);
        if (json != null) {
            try {
                JsonNode root = LENIENT.readTree(json);
                return new ReviewResult(findings(root.path("findings")),
                        root.path("summary").asText("").trim(), usage);
            } catch (Exception ignored) {
                // fall through to the degraded result
            }
        }
        // Degraded mode: never fail the review because the model rambled.
        return new ReviewResult(List.of(),
                modelOutput == null ? "" : modelOutput.strip(), usage);
    }

    private static List<Finding> findings(JsonNode array) {
        List<Finding> findings = new ArrayList<>();
        if (!array.isArray()) {
            return findings;
        }
        for (JsonNode node : array) {
            String path = node.path("path").asText("");
            int line = node.path("line").asInt(-1);
            if (path.isBlank() || line < 1) {
                continue; // unanchorable — drop rather than post a floating comment
            }
            int endLine = Math.max(line, node.path("endLine").asInt(line));
            String suggestion = node.path("suggestion").isNull() ? null : node.path("suggestion").asText(null);
            findings.add(new Finding(path, new LineRange(line, endLine),
                    severity(node.path("severity").asText("")),
                    node.path("message").asText("").trim(),
                    suggestion));
        }
        return findings;
    }

    private static Severity severity(String raw) {
        try {
            return Severity.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Severity.INFO;
        }
    }

    /**
     * Extracts the outermost {...} object directly — brace-first, so markdown
     * fences around it OR embedded ``` fences inside string values (e.g. a
     * fenced suggestion) never truncate the JSON (review finding L2).
     */
    private static String extractJson(String output) {
        if (output == null) {
            return null;
        }
        String text = output.strip();
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            return null;
        }
        return text.substring(start, end + 1);
    }
}
