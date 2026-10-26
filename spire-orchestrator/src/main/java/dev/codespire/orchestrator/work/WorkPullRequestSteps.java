package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.StepSummary;
import java.util.List;

/** RED stub: the steps section of a plan's pull request body. */
final class WorkPullRequestSteps {
    static final int MAX_CHARS = 32_000;

    record Line(String title, boolean empty, StepSummary summary) {}

    private WorkPullRequestSteps() {}

    static String section(List<Line> lines) {
        return "";
    }
}
