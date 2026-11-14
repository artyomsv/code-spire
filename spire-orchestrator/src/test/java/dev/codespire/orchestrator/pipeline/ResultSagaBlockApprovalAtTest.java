package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.orchestrator.repository.BlockApprovalAt;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static dev.codespire.orchestrator.pipeline.ResultSagaVerdictFixture.generated;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The saga counts open findings at the repository's "Block approval at" level when it decides the verdict
 * (V115, ADR-047). The fixture is shared with {@link ResultSagaVerdictTest}.
 */
class ResultSagaBlockApprovalAtTest {

    private final ResultSagaVerdictFixture fx = new ResultSagaVerdictFixture();

    @Test
    void aMajorOnlyReviewApprovesAtBlocker() {
        fx.majorOnly = true;
        fx.saga().on(generated());
        assertEquals(ReviewVerdict.APPROVE, assertInstanceOf(ActionCommand.PostComments.class, fx.emitted.getLast()).verdict());
        assertTrue(fx.timelineLines.contains("ReviewVerdictDecided verdict:APPROVE · assessment:APPROVE · no_open_blockers"),
                fx.timelineLines.toString());
    }

    @Test
    void aMajorOnlyReviewRequestsChangesAtMajorAndTheTimelineNamesTheLevel() {
        fx.majorOnly = true;
        fx.level = Optional.of(BlockApprovalAt.MAJOR);
        fx.saga().on(generated());
        assertEquals(ReviewVerdict.REQUEST_CHANGES,
                assertInstanceOf(ActionCommand.PostComments.class, fx.emitted.getLast()).verdict());
        assertTrue(fx.timelineLines.contains(
                "ReviewVerdictDecided verdict:REQUEST_CHANGES · assessment:REQUEST_CHANGES · open_at_or_above_major=1"),
                fx.timelineLines.toString());
    }

    @Test
    void aRepositoryWithNoLevelCountsAtBlocker() {
        fx.majorOnly = true;
        fx.level = Optional.empty();
        fx.saga().on(generated());
        assertEquals(ReviewVerdict.APPROVE, assertInstanceOf(ActionCommand.PostComments.class, fx.emitted.getLast()).verdict());
    }
}
