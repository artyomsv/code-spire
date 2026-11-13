package dev.codespire.harness.claudecode;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.harness.RunEvent;
import dev.codespire.harness.TokenBucket;
import dev.codespire.harness.UsageReport;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * One {@code --output-format stream-json} line to its normalized events. The shapes are those Claude Code 2.1.319
 * emitted in the 2026-11-11 measurement (harness reviews 3b, Task 3's record), not read from documentation:
 *
 * <ul>
 *   <li>{@code assistant}: {@code message.content} blocks of type {@code text}, {@code thinking} and
 *       {@code tool_use}. The measured lines carried one block each; every block of a line is still mapped.</li>
 *   <li>{@code user}: {@code tool_result} blocks naming their {@code tool_use_id}.</li>
 *   <li>{@code result}: the run's whole {@code usage}, and {@code is_error}. An error result was measured with
 *       {@code "subtype":"success"}, {@code "is_error":true} and exit 1, so {@code is_error} decides, never the
 *       subtype.</li>
 *   <li>{@code system} ({@code init}, {@code hook_started}, {@code api_retry} and the rest): nothing in this arm.</li>
 * </ul>
 */
final class ClaudeCodeEvents {

    /** The state a result line reports under; {@code classify} and the run worker's fold read it. */
    static final String RESULT = "result";

    /** The prefix of a result's detail when the CLI said it failed. */
    static final String ERROR_PREFIX = "error:";
    /** Where an error result names its cause, most specific first. */
    private static final List<String> ERROR_REASON_FIELDS = List.of("api_error_status", "api_error", "terminal_reason", "subtype");

    static final String SUCCESS = "success";

    /**
     * A tool's input and output and the model's text are model-controlled and unbounded, and every event becomes a row
     * on an operator's timeline: clipped on the way in, as Codex does.
     */
    private static final int MAX_SUMMARY_CHARS = 2_000;

    private static final ObjectMapper JSON = new ObjectMapper();

    private ClaudeCodeEvents() {
    }

    static List<RunEvent> of(String line) {
        JsonNode node = read(line);
        if (node == null) {
            return List.of();
        }
        Instant at = Instant.now();
        return switch (node.path("type").asText("")) {
            case "assistant" -> assistant(node, at);
            case "user" -> toolResults(node, at);
            case "result" -> result(node, at);
            default -> List.of();
        };
    }

    /** A line we cannot read is skipped, never fatal: the agent writes to the same stdout. */
    private static JsonNode read(String line) {
        if (line == null || line.isBlank()) {
            return null;
        }
        try {
            JsonNode node = JSON.readTree(line);
            return node != null && node.isObject() ? node : null;
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private static List<RunEvent> assistant(JsonNode node, Instant at) {
        List<RunEvent> events = new ArrayList<>();
        for (JsonNode block : node.path("message").path("content")) {
            switch (block.path("type").asText("")) {
                case "text" -> events.add(new RunEvent.Output(at, clip(block.path("text").asText(""))));
                case "thinking" -> events.add(new RunEvent.Thinking(at, clip(block.path("thinking").asText(""))));
                case "tool_use" -> events.add(new RunEvent.ToolUse(at, clip(block.path("name").asText("")),
                        clip(block.path("input").toString())));
                default -> { }
            }
        }
        return List.copyOf(events);
    }

    /** Stateless: a result names the tool call it answers by id, not by the tool's name. */
    private static List<RunEvent> toolResults(JsonNode node, Instant at) {
        List<RunEvent> events = new ArrayList<>();
        for (JsonNode block : node.path("message").path("content")) {
            if ("tool_result".equals(block.path("type").asText(""))) {
                events.add(new RunEvent.ToolResult(at, clip(block.path("tool_use_id").asText("")),
                        block.path("is_error").asBoolean(false), clip(contentText(block.path("content")))));
            }
        }
        return List.copyOf(events);
    }

    /** A tool result's content is a string, or a list of blocks whose text parts are joined. */
    private static String contentText(JsonNode content) {
        if (content.isTextual()) {
            return content.asText();
        }
        StringBuilder text = new StringBuilder();
        for (JsonNode part : content) {
            text.append(part.path("text").asText(""));
        }
        return text.toString();
    }

    private static List<RunEvent> result(JsonNode node, Instant at) {
        List<RunEvent> events = new ArrayList<>();
        UsageReport usage = usage(node.path("usage"));
        if (usage != null) {
            events.add(new RunEvent.Usage(at, usage));
        }
        events.add(new RunEvent.StateChange(at, RESULT, outcome(node)));
        return List.copyOf(events);
    }

    private static String outcome(JsonNode node) {
        if (!node.path("is_error").asBoolean(false)) {
            return SUCCESS;
        }
        return clip(ERROR_PREFIX + errorReason(node));
    }

    /**
     * Measured: an error result can say {@code "subtype":"success"} with {@code api_error_status} null and the cause in
     * {@code api_error} ({@code connection_failed}) or {@code terminal_reason}, so the first field that says something
     * names it; the subtype is the last resort.
     */
    private static String errorReason(JsonNode node) {
        for (String field : ERROR_REASON_FIELDS) {
            JsonNode value = node.path(field);
            if (value.isValueNode() && !value.isNull() && !value.asText().isBlank()) {
                return value.asText().trim();
            }
        }
        return "";
    }

    /**
     * The result's usage onto {@link TokenBucket}. Anthropic's four counts are disjoint, so nothing is subtracted:
     * cache reads and cache writes are additional to {@code input_tokens}, and thinking is billed as output. A count
     * that is missing reads as zero only beside counts that are present; no count, or one that is not a non-negative
     * number, is no measurement, and the run's usage stays unknown rather than zero.
     */
    private static UsageReport usage(JsonNode usage) {
        if (!usage.isObject()) {
            return null;
        }
        Map<String, TokenBucket> fields = Map.of("input_tokens", TokenBucket.INPUT,
                "cache_read_input_tokens", TokenBucket.CACHED_INPUT,
                "cache_creation_input_tokens", TokenBucket.CACHE_WRITE,
                "output_tokens", TokenBucket.OUTPUT);
        Map<TokenBucket, Long> counts = new EnumMap<>(TokenBucket.class);
        boolean anyPresent = false;
        for (Map.Entry<String, TokenBucket> field : fields.entrySet()) {
            JsonNode value = usage.path(field.getKey());
            boolean missing = value.isMissingNode() || value.isNull();
            if (!missing && (!value.isIntegralNumber() || value.asLong() < 0)) {
                return null;
            }
            anyPresent |= !missing;
            counts.put(field.getValue(), missing ? 0L : value.asLong());
        }
        return anyPresent ? UsageReport.of(counts) : null;
    }

    static String clip(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_SUMMARY_CHARS ? text : text.substring(0, MAX_SUMMARY_CHARS) + "…";
    }
}
