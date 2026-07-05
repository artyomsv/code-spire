package dev.codespire.orchestrator.readmodel;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The pipeline-stepper derivation used by the detail view (pure). */
class ComputeStagesTest {

    @Test
    void observedMarksOnlyReceived() {
        assertEquals(List.of("done", "pending", "pending", "pending", "pending", "pending"),
                ReviewProjection.computeStages("observed", 0));
    }

    @Test
    void reviewingMarksActiveStep() {
        assertEquals(List.of("done", "done", "active", "pending", "pending", "pending"),
                ReviewProjection.computeStages("reviewing", ReviewProjection.STAGE_CONTEXT));
    }

    @Test
    void completedMarksEveryStepDone() {
        assertEquals(List.of("done", "done", "done", "done", "done", "done"),
                ReviewProjection.computeStages("completed", ReviewProjection.STAGE_DONE));
    }

    @Test
    void failedMarksTheStalledStep() {
        assertEquals(List.of("done", "done", "done", "failed", "pending", "pending"),
                ReviewProjection.computeStages("failed", ReviewProjection.STAGE_REVIEW));
    }
}
