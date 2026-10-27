package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.StepSummary;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The steps section of a plan's pull request body (M4 slice 3, §6.6, A13): numbered titles, "empty" where a step changed
 * nothing, and each step's decisions, neutralised so agent-written text cannot mention anyone or render as HTML, and
 * capped so the body stays under the forge's limit.
 */
class WorkPullRequestStepsTest {

    @Test
    void theBodyListsTheStepsAndSummaries() {
        String section = WorkPullRequestSteps.section(List.of(
                new WorkPullRequestSteps.Line("TEST-step-1", false, new StepSummary(List.of("TEST-decision @TEST-person <b>bold</b>"),
                        List.of("TEST-alternative"), List.of("TEST-deviation"))),
                new WorkPullRequestSteps.Line("TEST-step-2 <i>", true, null)));

        assertTrue(section.contains("1. TEST-step-1"), section);
        assertTrue(section.contains("2. TEST-step-2 &lt;i> (empty)"), section);
        assertTrue(section.contains("TEST-decision @​TEST-person &lt;b>bold&lt;/b>"), section);
        assertTrue(section.contains("TEST-alternative"), section);
        assertTrue(section.contains("TEST-deviation"), section);
        assertFalse(section.contains("@TEST-person"), "an agent-written mention would notify someone");
        assertFalse(section.contains("<b>"), "agent-written HTML would render");
        assertTrue(section.contains("No decisions recorded"), section);
    }

    @Test
    void aDecisionCannotBreakTheList() {
        String section = WorkPullRequestSteps.section(List.of(
                new WorkPullRequestSteps.Line("TEST-step-1\n# TEST-heading", false, new StepSummary(List.of("TEST-line\n## TEST-injected"), List.of(), List.of()))));

        assertFalse(section.contains("\n# TEST-heading"), section);
        assertFalse(section.contains("\n## TEST-injected"), section);
    }

    @Test
    void theSectionStaysUnderTheForgeLimit() {
        List<String> decisions = Collections.nCopies(20, "TEST-" + "x".repeat(495));
        List<WorkPullRequestSteps.Line> lines = new ArrayList<>();
        for (int n = 1; n <= 50; n++) lines.add(new WorkPullRequestSteps.Line("TEST-step-" + n, false, new StepSummary(decisions, decisions, decisions)));

        String section = WorkPullRequestSteps.section(lines);

        assertTrue(section.length() <= WorkPullRequestSteps.MAX_CHARS, section.length() + " characters");
        assertTrue(section.contains("50. TEST-step-50"), "every step title is listed even when decisions are cut");
        assertTrue(section.contains("cut"), "the cut is said");
    }
}
