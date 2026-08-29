package dev.codespire.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.json.JsonMapper;
import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.FindingCategory;
import dev.codespire.diff.TokenBudget;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.Severity;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Lenient parser for the model's JSON review output, on the same premise
 * PR-Agent's try_fix_yaml is built on — LLMs emit almost-valid output — but
 * written against our JSON contract: strips fences, extracts the
 * outermost JSON object, tolerates trailing commas, and degrades gracefully
 * (raw text becomes the summary, zero findings) instead of failing a review.
 */
public final class FindingsParser {

    /** Bound on the degraded-mode summary (~4000 chars) — raw model ramble is otherwise unbounded. */
    private static final int DEGRADED_SUMMARY_MAX_TOKENS = 1250;

    private static final ObjectMapper LENIENT = JsonMapper.builder()
            .enable(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_TRAILING_COMMA)
            .enable(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_JAVA_COMMENTS)
            .enable(com.fasterxml.jackson.core.json.JsonReadFeature.ALLOW_SINGLE_QUOTES)
            .build();

    private FindingsParser() {
    }

    /** Prefix on a degraded summary so a parse failure is never mistaken for a clean review. */
    static final String DEGRADED_PREFIX =
            "Note: the model's response could not be parsed into structured findings "
                    + "(no inline comments were posted). Its raw output follows:\n\n";

    /**
     * Takes the whole {@link Completion} rather than its text and usage separately, so
     * {@code outputCapped} cannot be forgotten at a call site or transposed with another boolean.
     */
    public static ReviewResult parse(Completion completion) {
        ModelUsage usage = completion.usage();
        JsonNode root = readLenient(completion.text());
        if (root != null) {
            // Parsed — but a response the provider cut off at its output limit is a PARTIAL finding
            // set, not a finished review. That reads as clean without this, and raising the output
            // cap makes it the likelier remnant rather than removing it.
            return new ReviewResult(findings(root.path("findings")),
                    root.path("summary").asText("").trim(), usage, false, completion.outputCapped());
        }
        // Degraded mode: never fail the review because the model rambled, but a
        // "0 findings" degraded parse must NOT look like a clean pass — mark it
        // explicitly (a truncated/reasoning-model answer is a false negative otherwise).
        String raw = completion.text() == null ? "" : completion.text().strip();
        String summary = raw.isEmpty()
                ? "Note: the model returned no output — the review could not be produced."
                : DEGRADED_PREFIX + TokenBudget.clip(raw, DEGRADED_SUMMARY_MAX_TOKENS);
        return ReviewResult.degraded(summary, usage);
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
                    // Unrecognised or absent -> null, never OTHER: OTHER is an answer the model gave,
                    // an unparseable label is unknown, and ADR-023 is the standing lesson about
                    // collapsing those two into one value.
                    FindingCategory.parse(node.path("category").asText(null)),
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

    /** Package-private: lenient extract+parse shared with VerdictsParser. Null when unusable. */
    static JsonNode readLenient(String output) {
        String json = extractJson(output);
        if (json == null) {
            return null;
        }
        try {
            return LENIENT.readTree(json);
        } catch (Exception e) {
            return null;
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
