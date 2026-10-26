package dev.codespire.orchestrator.work;

import java.util.List;

/** RED: the prompt of plan step k (M4 slice 3, §6.6) is not built yet. */
final class StepPrompts {
    private StepPrompts() {}

    record Input(String specification, List<WorkArtifacts.Step> steps, int index, List<WorkStepSummaries.Step> summaries, String verifySection) {}

    static String prompt(Input input) {
        return "";
    }

    static String summaries(List<WorkStepSummaries.Step> summaries, int index) {
        return "";
    }
}
