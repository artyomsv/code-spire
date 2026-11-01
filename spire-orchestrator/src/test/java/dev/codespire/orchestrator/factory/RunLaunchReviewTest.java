package dev.codespire.orchestrator.factory;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

/** A review run rides the review lane, so a review never waits behind an hour-long build on the build lane. */
class RunLaunchReviewTest {

    private static final String RUN_ID = "run::github:TEST-WS/TEST-REPO:review-7-0123456789ab-0123abcd:1";

    @Test
    void aReviewRunIsSentOnTheReviewLane() {
        List<String> lanes = new ArrayList<>();
        RunLaunch launch = new RunLaunch();
        launch.emitter = new RunCommandEmitter() {
            @Override public void dispatch(RunCommand command) { lanes.add("run-commands-out"); }
            @Override public void dispatchReview(RunCommand command) { lanes.add("review-runs-out"); }
            @Override public void control(RunCommand command) { lanes.add("run-control-out"); }
        };
        launch.projection = new FactoryRunProjection() {
            @Override protected void push(String runId) { throw new AssertionError("unexpected live broadcast"); }
            @Override public void dispatchFailed(String runId, String detail) { throw new AssertionError("it was acknowledged"); }
            @Override public void dispatchUncertain(String runId, String detail) { throw new AssertionError("it was acknowledged"); }
        };

        RunLaunch.Outcome outcome = launch.launch(command());

        assertInstanceOf(RunLaunch.Dispatched.class, outcome);
        assertEquals(List.of("review-runs-out"), lanes);
    }

    private static RunCommand.ExecuteReviewRun command() {
        String head = "0123456789abcdef0123456789abcdef01234567";
        RunCommand.ExecuteRun execution = new RunCommand.ExecuteRun(RUN_ID, new RepoRef("TEST-WS", "TEST-REPO"),
                "https://forge.example.test/TEST-WS/TEST-REPO.git", "TEST-source", head, "TEST-source", "TEST-prompt",
                "TEST-harness", "TEST-model", "TEST-image", List.of(), 600, "TEST-scm", "TEST-key");
        return new RunCommand.ExecuteReviewRun(RUN_ID, execution,
                new ReviewRunBinding("review::TEST-WS/TEST-REPO#7", ReviewRunBinding.Kind.REVIEW, 7, head, "TEST-slot", 1));
    }
}
