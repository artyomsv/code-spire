package dev.codespire.orchestrator.pipeline;

import dev.codespire.orchestrator.readmodel.AnsweringMarks;
import dev.codespire.orchestrator.readmodel.ReviewProjection;

/**
 * Builds {@link FollowUpEndings} by hand for a test in another package, so its marks and projection can be the ones the
 * test counts (qa/M-2, review of PR #457). The thread view stays unset: a posted answer that names its comment never
 * reaches it.
 */
public final class FollowUpEndingsWiring {

    private FollowUpEndingsWiring() {
    }

    public static FollowUpEndings over(ReviewProjection projection, AnsweringMarks marks) {
        FollowUpEndings endings = new FollowUpEndings();
        endings.projection = projection;
        endings.answering = marks;
        return endings;
    }
}
