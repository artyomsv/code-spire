package dev.codespire.harness.claudecode;

import dev.codespire.contract.llm.HarnessTokenReport;
import dev.codespire.contract.review.TokenType;
import dev.codespire.harness.RunEventSummary;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What this arm reports must be what the orchestrator was told it reports: the orchestrator refuses a model it cannot
 * price through {@link HarnessTokenReport} and never sees the adapter, so a drift between the two would pass the gate
 * and stop the run with an unknown cost. The fixture reports every bucket non-zero, so a short declaration cannot pass.
 */
class ClaudeCodeReportsWhatTheContractDeclaresTest {

    private final ClaudeCodeAdapter adapter = new ClaudeCodeAdapter();

    @Test
    void theAdapterReportsExactlyTheDeclaredBuckets() {
        Set<TokenType> reported = adapter.usage(RunEventSummary.of(adapter.parseAll(ClaudeCodeLines.RESULT_SUCCESS)))
                .asMap().orElseThrow(() -> new AssertionError("the result line reported nothing")).keySet().stream()
                .map(bucket -> TokenType.valueOf(bucket.name())).collect(Collectors.toUnmodifiableSet());

        assertEquals(HarnessTokenReport.reportedBy("claude_code"), reported,
                "HarnessTokenReport must declare exactly the types ClaudeCodeAdapter can report");
        assertTrue(HarnessTokenReport.declared().contains("claude_code"));
    }
}
