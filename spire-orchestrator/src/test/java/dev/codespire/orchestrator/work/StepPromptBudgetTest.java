package dev.codespire.orchestrator.work;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The worst-case step prompt (M4 slice 3, A8): the specification, the numbered titles, the largest step, and room for
 * the summaries and the verify section, checked when the plan is accepted, so no step is refused after approval.
 */
class StepPromptBudgetTest {
    static final String SPEC_16_KIB = "x".repeat(16 * 1024);

    static WorkArtifacts.Step step(int n, String instruction) {
        return new WorkArtifacts.Step("TEST-s" + n, "TEST-step-" + n, instruction, "TEST-done-" + n);
    }

    @Test
    void aPlanOverThePromptBudgetIsInvalid() {
        List<WorkArtifacts.Step> steps = IntStream.rangeClosed(1, 40)
                .mapToObj(n -> new WorkArtifacts.Step("TEST-s" + n, "t".repeat(110), "TEST-instruction", "TEST-done")).toList();

        Optional<String> refused = StepPromptBudget.refusal(SPEC_16_KIB, steps);

        assertTrue(refused.isPresent(), "40 titles of 110 characters are over 4 KiB");
        assertTrue(refused.orElseThrow().contains("plan titles"), refused.orElseThrow());
    }

    @Test
    void aPlanWithinTheBudgetPasses() {
        assertEquals(Optional.empty(), StepPromptBudget.refusal(SPEC_16_KIB, List.of(step(1, "TEST-instruction"), step(2, "TEST-instruction"))));
    }

    @Test
    void theWorstStepDecides() {
        List<WorkArtifacts.Step> steps = new ArrayList<>(List.of(step(1, "TEST-small"), step(2, "TEST-small")));
        WorkArtifacts.Step large = step(3, "y".repeat(4100));
        steps.add(large);
        steps.add(step(4, "TEST-small"));
        long titles = IntStream.range(0, steps.size()).mapToLong(i -> ((i + 1) + ". " + steps.get(i).title() + "\n").length()).sum();

        assertEquals(SPEC_16_KIB.length() + titles + 4100 + large.doneWhen().length() + StepPromptBudget.SUMMARIES_BYTES + StepPromptBudget.VERIFY_BYTES,
                StepPromptBudget.worstCase(SPEC_16_KIB, steps));
        Optional<String> refused = StepPromptBudget.refusal(SPEC_16_KIB, steps);
        assertTrue(refused.orElseThrow().contains("largest step"), refused.orElseThrow());
    }

    @Test
    void bytesAreCountedNotCharacters() {
        // 1,400 three-byte characters are 4,200 bytes: over the 4 KiB step part though under 4,096 characters.
        Optional<String> refused = StepPromptBudget.refusal("TEST-spec", List.of(step(1, "€".repeat(1400))));

        assertTrue(refused.orElseThrow().contains("largest step"), refused.orElseThrow());
    }

    /**
     * The fence a quoted part is given outgrows its longest backtick run on both sides (A13), so a specification within
     * its 16 KiB share can still take twice that in the prompt: the budget counts what the prompt holds.
     */
    @Test
    void theFencesCount() {
        String specification = "## Outcome\nTEST\n## Context\n" + "`".repeat(16_000) + "\n## Acceptance criteria\n- TEST";
        assertTrue(specification.length() <= StepPromptBudget.SPECIFICATION_BYTES);

        Optional<String> refused = StepPromptBudget.refusal(specification, List.of(step(1, "TEST-small"), step(2, "TEST-small")));

        assertTrue(refused.isPresent(), "the fenced specification leaves no room for the summaries and the verify section");
    }

    @Test
    void aSpecificationOver16KiBIsNamed() {
        Optional<String> refused = StepPromptBudget.refusal(SPEC_16_KIB + "x", List.of(step(1, "TEST-small")));

        assertTrue(refused.orElseThrow().contains("specification"), refused.orElseThrow());
    }
}
