package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.StepSummary;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The prompt of plan step k (M4 slice 3, §6.6, A8, A13): the stored specification, the plan as numbered titles, the
 * current step, and every earlier step's decisions, oldest first, cut to 12 KiB with the newest kept whole. Every text a
 * person or an agent wrote is quoted as data.
 */
class StepPromptsTest {
    static final String SPEC = "## Outcome\nTEST-outcome\n## Context\nTEST-context";
    static final List<WorkArtifacts.Step> STEPS = List.of(
            new WorkArtifacts.Step("TEST-s1", "TEST-step-1", "TEST-instruction-1", "TEST-done-1"),
            new WorkArtifacts.Step("TEST-s2", "TEST-step-2", "TEST-instruction-2", "TEST-done-2"),
            new WorkArtifacts.Step("TEST-s3", "TEST-step-3", "TEST-instruction-3", "TEST-done-3"));

    static WorkStepSummaries.Step summary(int index, String... decisions) {
        return new WorkStepSummaries.Step(index, "TEST-run-" + index, "recorded", new StepSummary(List.of(decisions), List.of(), List.of()));
    }

    static String prompt(int index, List<WorkStepSummaries.Step> summaries) {
        return StepPrompts.prompt(new StepPrompts.Input(SPEC, STEPS, index, summaries, ""));
    }

    @Test
    void stepThreesPromptCarriesStepOnesDecision() {
        String prompt = prompt(2, List.of(summary(0, "TEST-decision-1"), summary(1, "TEST-decision-2")));

        assertTrue(prompt.contains("TEST-decision-1"), prompt);
        assertTrue(prompt.contains("TEST-decision-2"), prompt);
        assertTrue(prompt.indexOf("TEST-decision-1") < prompt.indexOf("TEST-decision-2"), "oldest first");
        assertTrue(prompt.contains("Now do step 3 of 3"), prompt);
    }

    @Test
    void aStepSeesOnlyEarlierSteps() {
        String prompt = prompt(1, List.of(summary(0, "TEST-decision-1"), summary(1, "TEST-decision-of-a-failed-try")));

        assertTrue(prompt.contains("TEST-decision-1"), prompt);
        assertFalse(prompt.contains("TEST-decision-of-a-failed-try"), "a retried step's own earlier summary is not an earlier step");
    }

    @Test
    void summariesAreCutOldestFirst() {
        List<WorkStepSummaries.Step> summaries = new ArrayList<>();
        for (int step = 0; step < 10; step++) {
            List<String> decisions = new ArrayList<>();
            for (int item = 0; item < 4; item++) decisions.add("TEST-s" + step + "-d" + item + "-" + "x".repeat(480));
            summaries.add(new WorkStepSummaries.Step(step, "TEST-run-" + step, "recorded", new StepSummary(decisions, List.of(), List.of())));
        }

        String section = StepPrompts.summaries(summaries, 10);

        assertTrue(section.getBytes(StandardCharsets.UTF_8).length <= StepPromptBudget.SUMMARIES_BYTES,
                "the summaries take " + section.getBytes(StandardCharsets.UTF_8).length + " bytes");
        for (String decision : summaries.getLast().summary().decisions()) assertTrue(section.contains(decision), "the newest step is kept whole");
        assertFalse(section.contains("TEST-s0-d3-"), "the oldest step is cut first");
    }

    @Test
    void aMissingSummaryIsSaidPlainly() {
        String prompt = prompt(2, List.of(new WorkStepSummaries.Step(1, "TEST-run-1", "summary_missing", null)));

        assertTrue(prompt.contains("Step 1 recorded no decisions"), prompt);
        assertTrue(prompt.contains("Step 2 recorded no decisions"), prompt);
    }

    @Test
    void everyQuotedPartIsFenced() {
        String prompt = prompt(2, List.of(summary(0, "TEST-decision-1"), summary(1, "TEST-decision-2")));

        assertTrue(prompt.contains(UntrustedText.fenced("The approved specification", SPEC)), prompt);
        assertTrue(prompt.contains(UntrustedText.fenced("The plan", "1. TEST-step-1\n2. TEST-step-2\n3. TEST-step-3")), prompt);
        assertTrue(prompt.contains(UntrustedText.fenced("Step 3", "TEST-step-3\n\nTEST-instruction-3\n\nDone when: TEST-done-3")), prompt);
        String summaries = StepPrompts.summaries(List.of(summary(0, "TEST-decision-1"), summary(1, "TEST-decision-2")), 2);
        assertTrue(summaries.startsWith(UntrustedText.DATA_HEADING), summaries);
        assertTrue(prompt.contains(summaries), prompt);
    }

    @Test
    void aSummaryCannotCloseItsFence() {
        String section = StepPrompts.summaries(List.of(summary(0, "TEST-```` closing attempt")), 1);

        assertTrue(section.contains("\n`````\n"), section);
    }

    @Test
    void aRetryCarriesItsFailureSection() {
        String prompt = StepPrompts.prompt(new StepPrompts.Input(SPEC, STEPS, 1, List.of(summary(0, "TEST-decision-1")),
                "\n\nThe previous build of this task did not pass verification.\nTEST-failure"));

        assertTrue(prompt.contains("TEST-failure"), prompt);
        assertTrue(prompt.indexOf("TEST-decision-1") < prompt.indexOf("TEST-failure"), "the failure section comes last");
    }
}
