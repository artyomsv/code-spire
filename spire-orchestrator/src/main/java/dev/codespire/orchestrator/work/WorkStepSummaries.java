package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.work.StepSummary;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.util.List;

/** RED: nothing is stored yet. */
@ApplicationScoped
public class WorkStepSummaries {
    public record Step(int stepIndex, String runId, String state, StepSummary summary) {}

    void record(Connection c, RunResult.RunWorkReady ready, int stepIndex) {
        throw new UnsupportedOperationException("RED");
    }

    public List<Step> list(String workItemId, long generation) {
        throw new UnsupportedOperationException("RED");
    }
}
