package dev.codespire.harness.codex;

import dev.codespire.contract.llm.HarnessTokenReport;
import dev.codespire.contract.review.TokenType;
import dev.codespire.harness.RunEvent;
import dev.codespire.harness.RunEventSummary;
import dev.codespire.harness.TokenBucket;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What this arm reports must be what the orchestrator was told it reports.
 *
 * <p>The orchestrator refuses a run whose model has no rate for a type the harness will report
 * ({@code model_pricing_incomplete}). It reaches that decision through
 * {@link HarnessTokenReport}, because it has no dependency on the harness tier and never sees an
 * adapter. So the declaration can drift from the adapter silently, and the symptom of drift is the
 * defect the check exists to prevent: a run that passes the gate, spends, reports a type nobody
 * priced, and stops the item with an unknown cost.
 *
 * <p>The usage line below reports all five types with non-zero counts on purpose. Cache writes have
 * measured zero on every run so far, and a fixture that copies an observed run would let a
 * four-type declaration pass — the exact hole this pins shut.
 */
class CodexReportsWhatTheContractDeclaresTest {

    private final CodexAdapter adapter = new CodexAdapter();

    @Test
    void theContractDeclaresEveryTypeThisArmCanReport() {
        String line = """
                {"type":"turn.completed","usage":{"input_tokens":1000,"cached_input_tokens":400,\
                "cache_write_input_tokens":50,"output_tokens":300,"reasoning_output_tokens":120}}""";
        RunEvent event = adapter.parse(line).orElseThrow(() -> new AssertionError("the usage line was not parsed"));
        Set<TokenType> reported = adapter.usage(RunEventSummary.of(List.of(event))).asMap()
                .orElseThrow(() -> new AssertionError("the usage line reported nothing")).keySet().stream()
                .map(bucket -> TokenType.valueOf(bucket.name())).collect(Collectors.toUnmodifiableSet());

        assertEquals(reported, HarnessTokenReport.reportedBy("codex"),
                "HarnessTokenReport must declare exactly the types CodexAdapter can report");
        assertTrue(reported.contains(TokenType.CACHE_WRITE),
                "the fixture must exercise a non-zero cache write, or a four-type declaration passes");
        assertTrue(HarnessTokenReport.declared().contains("codex"));
    }

    /** TOTAL is the unreconciled case and can never carry a rate, so it is never part of completeness. */
    @Test
    void theUnreconciledTotalIsNotADeclaredType() {
        assertTrue(HarnessTokenReport.reportedBy("codex").stream().noneMatch(type -> type == TokenType.TOTAL));
        assertTrue(HarnessTokenReport.EVERY_PRICEABLE_TYPE.stream().noneMatch(type -> type == TokenType.TOTAL));
        assertEquals(TokenBucket.values().length - 1, HarnessTokenReport.EVERY_PRICEABLE_TYPE.size());
    }

    /** A harness this build does not know is assumed to report everything; guessing low spends money. */
    @Test
    void anUnknownHarnessIsAssumedToReportEveryPriceableType() {
        assertEquals(HarnessTokenReport.EVERY_PRICEABLE_TYPE, HarnessTokenReport.reportedBy("TEST-unknown-harness"));
        assertEquals(HarnessTokenReport.EVERY_PRICEABLE_TYPE, HarnessTokenReport.reportedBy(null));
    }
}
