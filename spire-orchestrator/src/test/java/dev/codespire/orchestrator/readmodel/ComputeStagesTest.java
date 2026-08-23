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

    /**
     * A refusal is not a failure and not a blank page. Falling to {@code default} drew all six nodes
     * grey, so a review refused at the pre-spend gate — which HAD fetched its diff and assembled its
     * context — told the operator that neither had happened.
     *
     * <p>Shaped like {@code cancelled}/{@code superseded} rather than {@code failed}: the work that did
     * happen is done, and no step is marked failed, because nothing failed. That is the same reason
     * ADR-025 gives for the status being {@code refused} rather than {@code failed} in the first place.
     */
    @Test
    void refusedMarksTheStepsThatDidRunWithoutMarkingAnyFailed() {
        assertEquals(List.of("done", "done", "done", "pending", "pending", "pending"),
                ReviewProjection.computeStages("refused", ReviewProjection.STAGE_REVIEW));
        assertEquals(List.of("done", "pending", "pending", "pending", "pending", "pending"),
                ReviewProjection.computeStages("refused", ReviewProjection.STAGE_DIFF),
                "the diff-size gate refuses one step earlier, and the stepper must say so");
    }
}
