package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;

/**
 * ROADMAP M0 exit criterion 2, the half that reaches the operator: a run the push gate refused
 * "raises an attention row naming the paths".
 */
@QuarkusTest
@TestSecurity(user = "op", roles = {"spire-viewer", "spire-admin"})
class RunAttentionTest {

    private static final String CODE = "RUN_PUSH_GATE_REFUSED";

    @Inject
    FactoryRunProjection projection;

    private String run() {
        String runId = "run::github:TEST-acme/app:att-" + UUID.randomUUID() + ":1";
        projection.queued(new FactoryRunProjection.QueuedRun(runId, "codex", "gpt-5.6", "main", "abc1234", "spire/x", "spire-bot", null));
        projection.apply(new RunResult.RunStarted(runId, "container-1"));
        return runId;
    }

    @Test
    void aRefusedRunRaisesARowNamingTheBlockedPathsUntilAcknowledged() {
        String runId = run();
        projection.apply(new RunResult.RunFinished(runId, null,
                List.of(".github/workflows/ci.yml", "src/App.java"),
                List.of(new RunResult.BlockedChange(".github/workflows/ci.yml", "DELETED")),
                null, false));

        given().when().get("/api/attention")
                .then().statusCode(200)
                .body("code", hasItem(CODE))
                .body("find { it.subject == '" + runId + "' }.message", containsString(".github/workflows/ci.yml"))
                // The sentence an operator actually reads has to carry the kind. Deleting a
                // workflow and editing one call for different responses, and this row is the
                // only place either is reported today.
                .body("find { it.subject == '" + runId + "' }.message", containsString("(deleted)"))
                .body("find { it.subject == '" + runId + "' }.message", not(containsString("src/App.java")));

        // The panel's contract: fixing the cause removes the row. Nothing un-refuses a run, so the
        // operator's acknowledgement is the fix — and it clears THIS run's row only.
        given().when().post("/api/runs/" + runId + "/attention-ack")
                .then().statusCode(204);
        given().when().get("/api/attention")
                .then().statusCode(200)
                .body("subject", not(hasItem(runId)));
    }

    @Test
    void aSucceededRunRaisesNothing() {
        String runId = run();
        projection.apply(new RunResult.RunFinished(runId, "refs/heads/spire/x", List.of("src/App.java"),
                List.of(), null, false));

        given().when().get("/api/attention")
                .then().statusCode(200)
                .body("subject", not(hasItem(runId)));
    }

    /**
     * An unacknowledged dispatch is surfaced, and the row goes away when it is resolved (FR-F10).
     *
     * <p>Not dismissable, unlike the gate-refusal row above. That one describes something already
     * over; this one describes a run that may be executing right now, so silencing it would leave
     * that untracked. The panel's contract — fixing the cause removes the row — is met by resolving
     * the dispatch.
     */
    @Test
    void anUncertainDispatchRaisesARowUntilItIsResolved() {
        String runId = "run::github:TEST-acme/app:att-" + UUID.randomUUID() + ":1";
        projection.queued(new FactoryRunProjection.QueuedRun(runId, "codex", "gpt-5.6", "main",
                "abc1234", "spire/x", "spire-bot", null));
        projection.dispatchUncertain(runId, "TEST-no ack");

        given().when().get("/api/attention")
                .then().statusCode(200)
                .body("subject", hasItem(runId))
                .body("find { it.subject == '" + runId + "' }.message",
                        containsString("never acknowledged"));

        given().contentType("application/json").body("{\"neverRan\":true}")
                .when().post("/api/runs/" + runId + "/dispatch-resolution")
                .then().statusCode(204);

        given().when().get("/api/attention")
                .then().statusCode(200)
                .body("subject", not(hasItem(runId)));
    }

    /**
     * The other way the row clears, and the one that happens without anybody being asked: the record
     * did land after all, and the run's own start says so.
     */
    @Test
    void aRunThatStartsAfterAllClearsItsOwnRow() {
        String runId = "run::github:TEST-acme/app:att-" + UUID.randomUUID() + ":1";
        projection.queued(new FactoryRunProjection.QueuedRun(runId, "codex", "gpt-5.6", "main",
                "abc1234", "spire/x", "spire-bot", null));
        projection.dispatchUncertain(runId, "TEST-no ack");

        projection.apply(new RunResult.RunStarted(runId, "container-1"));

        given().when().get("/api/attention")
                .then().statusCode(200)
                .body("subject", not(hasItem(runId)));
    }

    @Test
    void acknowledgingAnUnknownRunIs404() {
        given().when().post("/api/runs/run::github:TEST-none/x:y:1/attention-ack")
                .then().statusCode(404);
    }
}
