package dev.codespire.orchestrator.factory;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.orchestrator.provider.ProviderInput;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;
import static org.hamcrest.Matchers.startsWith;

@QuarkusTest
class RunResourceTest {

    @Inject
    ProviderRegistry providers;

    @Inject
    FactoryRunProjection projection;

    private static String body(String workspace) {
        return """
                {"workspace":"%s","slug":"app","providerType":"github",
                 "baseCommit":"abc1234","prompt":"fix the typo",
                 "harness":"codex","model":"gpt-5.6"}
                """.formatted(workspace);
    }

    private String workspaceWithFactoryAccount() {
        String workspace = "TEST-ws-" + UUID.randomUUID().toString().substring(0, 8);
        providers.create(new ProviderInput("factory-bot", "github", "https://api.github.com", workspace,
                "bearer", null, "TEST-factory-token", "", true, List.of(), "factory-bot", null, "FACTORY"));
        return workspace;
    }

    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void dispatchingReturnsADerivedRunId() {
        String workspace = workspaceWithFactoryAccount();

        String runId = given().contentType("application/json").body(body(workspace))
                .when().post("/api/runs")
                .then().statusCode(201)
                .body("runId", startsWith("run::github:" + workspace + "/app:"))
                .extract().path("runId");

        // Recorded before dispatch, so the run exists in the read model the moment 201 is answered.
        given().when().get("/api/runs/" + runId)
                .then().statusCode(200)
                .body("status", org.hamcrest.Matchers.equalTo(FactoryRunProjection.QUEUED));
    }

    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void aWorkspaceWithNoFactoryAccountIsRefusedNotPushedAsTheReviewer() {
        // 409, naming what is missing. The alternative — falling back to the reviewer's credential —
        // produces a branch the reviewer's own author allowlist skips: work nobody reviews and
        // nobody is told about.
        String workspace = "TEST-rev-" + UUID.randomUUID().toString().substring(0, 8);
        providers.create(new ProviderInput("reviewer-bot", "github", "https://api.github.com", workspace,
                "bearer", null, "TEST-reviewer-token", "", true, List.of(), "reviewer-bot", null));

        given().contentType("application/json").body(body(workspace))
                .when().post("/api/runs")
                .then().statusCode(409)
                .body(containsString("FACTORY"));
    }

    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void aDispatchTheBrokerNeverAcknowledgedIsRecordedAsFailedAndARetryReArmsIt() {
        // An ack timeout proves nothing about whether the record landed. So the row is neither
        // deleted (a run possibly on the bus with no record — the very thing writing it first
        // prevents) nor left as a queued run nobody will ever start: it is marked, and the same
        // request re-arms it. If the first dispatch did land, the worker's claim drops the second
        // and the first run's results project onto the re-armed row.
        String workspace = workspaceWithFactoryAccount();
        String request = body(workspace).replace("\"harness\"", "\"subject\":\"retry-me\",\"harness\"");
        String runId = "run::github:" + workspace + "/app:retry-me:1";

        QuarkusMock.installMockForType(new RunCommandEmitter() {
            @Override
            public void dispatch(RunCommand command) {
                throw new IllegalStateException("No broker ack within 10s for run command ExecuteRun");
            }
        }, RunCommandEmitter.class);

        given().contentType("application/json").body(request)
                .when().post("/api/runs")
                .then().statusCode(503)
                .body(containsString("re-arms"));
        given().when().get("/api/runs/" + runId)
                .then().statusCode(200)
                .body("status", equalTo(FactoryRunProjection.FAILED))
                .body("failureCause", equalTo(FactoryRunProjection.DISPATCH_FAILED));

        QuarkusMock.installMockForType(new RunCommandEmitter() {
            @Override
            public void dispatch(RunCommand command) {
                // the broker is back
            }
        }, RunCommandEmitter.class);

        given().contentType("application/json").body(request)
                .when().post("/api/runs")
                .then().statusCode(201);
        given().when().get("/api/runs/" + runId)
                .then().statusCode(200)
                .body("status", equalTo(FactoryRunProjection.QUEUED))
                .body("failureCause", nullValue());
    }

    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void aHarnessWithNoConfiguredImageIsRefusedBeforeAnythingIsRecorded() {
        // The orchestrator never names an arm; it reads spire.factory.agent-image.<harness>. A name
        // with no image is a 400 that names the configured ones — and leaves no queued row behind.
        String workspace = workspaceWithFactoryAccount();
        String request = body(workspace).replace("\"harness\":\"codex\"",
                "\"subject\":\"no-such-arm\",\"harness\":\"opencode\"");

        given().contentType("application/json").body(request)
                .when().post("/api/runs")
                .then().statusCode(400)
                .body(containsString("opencode"))
                .body(containsString("codex"));
        given().when().get("/api/runs/run::github:" + workspace + "/app:no-such-arm:1")
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void aSubjectGitWouldRefuseAsABranchIsRefusedHere() {
        String workspace = workspaceWithFactoryAccount();
        for (String bad : List.of("a..b", "x.lock", "has/slash", "-leading")) {
            String request = body(workspace).replace("\"harness\"", "\"subject\":\"" + bad + "\",\"harness\"");
            given().contentType("application/json").body(request)
                    .when().post("/api/runs")
                    .then().statusCode(400);
        }
    }

    @Test
    @TestSecurity(user = "viewer", roles = "spire-viewer")
    void aViewerCannotSpendMoney() {
        given().contentType("application/json").body(body("acme"))
                .when().post("/api/runs")
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void aMalformedRequestIsRefusedBeforeAnythingIsRecorded() {
        given().contentType("application/json")
                .body("""
                        {"workspace":"../etc","slug":"app","providerType":"github",
                         "baseCommit":"not-hex","prompt":"x","harness":"codex","model":"m"}
                        """)
                .when().post("/api/runs")
                .then().statusCode(400);

        given().contentType("application/json")
                .body("""
                        {"workspace":"acme","slug":"app","providerType":"mercurial",
                         "baseCommit":"abc1234","prompt":"x","harness":"codex","model":"m"}
                        """)
                .when().post("/api/runs")
                .then().statusCode(400);
    }

    @Test
    void anAnonymousCallerIsRefused() {
        given().contentType("application/json").body(body("acme"))
                .when().post("/api/runs")
                .then().statusCode(401);
    }
}
