package dev.codespire.orchestrator.work;

import java.util.List;
import java.util.Optional;

final class StepPromptBudget {
    static final int SUMMARIES_BYTES = 12 * 1024, VERIFY_BYTES = 8 * 1024;

    private StepPromptBudget() {}

    static long worstCase(String specification, List<WorkArtifacts.Step> steps) { throw new UnsupportedOperationException("RED"); }

    static Optional<String> refusal(String specification, List<WorkArtifacts.Step> steps) { return Optional.empty(); }
}
