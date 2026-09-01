package dev.codespire.orchestrator.factory;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.review.TokenType;
import dev.codespire.orchestrator.caps.CapPolicy;
import dev.codespire.orchestrator.caps.SpendWindow;
import dev.codespire.orchestrator.llm.ChargeCall;
import dev.codespire.orchestrator.llm.ChargeKind;
import dev.codespire.orchestrator.llm.ChargeLine;
import dev.codespire.orchestrator.llm.LlmProviderInput;
import dev.codespire.orchestrator.llm.LlmProviderRegistry;
import dev.codespire.orchestrator.provider.ProviderInput;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.settings.AppSettingRepository;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
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
                 "baseCommit":"0123456789abcdef0123456789abcdef01234567","prompt":"fix the typo",
                 "harness":"codex","model":"gpt-5.6"}
                """.formatted(workspace);
    }

    @Inject
    DataSource dataSource;

    /** Obviously synthetic, and not the repo-wide TEST-MODEL: priceability lives in a shared table. */
    private static final String MODEL = "TEST-RUN-MODEL";

    private void sql(String statement) {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + statement, e);
        }
    }

    @Inject
    LlmProviderRegistry llmProviders;

    /**
     * A default LLM provider — the harness credential's source. Created through the registry so the
     * key is encrypted the way a real one is (a raw INSERT of a plaintext key does not read back:
     * resolution decrypts). The catalog row is what the registry's priceability guard requires.
     */
    private UUID defaultLlmProvider() {
        sql("DELETE FROM llm_provider WHERE name = 'TEST-run-llm'");
        sql("DELETE FROM llm_model WHERE name = '" + MODEL + "'");
        sql("INSERT INTO llm_model (id, type, name, label, pricing_mode) VALUES ('" + UUID.randomUUID()
                + "', 'openai', '" + MODEL + "', '" + MODEL + "', 'UNMETERED')");
        return UUID.fromString(llmProviders.create(new LlmProviderInput("TEST-run-llm", "openai", "https://api.openai.com",
                "TEST-harness-key", MODEL, null, null, true, true)).id());
    }

    private String workspaceWithFactoryAccount() {
        defaultLlmProvider();
        String workspace = "TEST-ws-" + UUID.randomUUID().toString().substring(0, 8);
        providers.create(new ProviderInput("factory-bot", "github", "https://api.github.com", workspace,
                "bearer", null, "TEST-factory-token", "", true, List.of(), "factory-bot", null, "FACTORY"));
        return workspace;
    }

    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void theHarnessCredentialComesFromTheLlmRegistryNeverTheRequest() {
        // No default provider: refused, naming what to configure. A named provider: accepted. The
        // key itself is never in the body — the registry is where operator keys live, encrypted.
        String workspace = workspaceWithFactoryAccount();
        sql("DELETE FROM llm_provider");
        given().contentType("application/json").body(body(workspace))
                .when().post("/api/runs")
                .then().statusCode(409)
                .body(containsString("LLM provider"));

        UUID id = defaultLlmProvider();
        sql("UPDATE llm_provider SET is_default = false WHERE id = '" + id + "'");
        given().contentType("application/json").body(body(workspace))
                .when().post("/api/runs")
                .then().statusCode(409);
        given().contentType("application/json")
                .body(body(workspace).replace("\"harness\"", "\"llmProviderId\":\"" + id + "\",\"harness\""))
                .when().post("/api/runs")
                .then().statusCode(201);

        given().contentType("application/json")
                .body(body(workspace).replace("\"harness\"", "\"llmProviderId\":\"not-a-uuid\",\"harness\""))
                .when().post("/api/runs")
                .then().statusCode(400);
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
    void dispatchingAQueuedSubjectAgainIsRefusedNotSilentlyDropped() {
        // The worker's claim drops a second ExecuteRun as a redelivery, so answering 201 to a repeat
        // would promise a run that never starts. The earlier version overwrote the row and answered
        // 201 anyway; now the row is written only when absent or re-armable, and a repeat is a 409
        // that names the run and its status.
        String workspace = workspaceWithFactoryAccount();
        String request = body(workspace).replace("\"harness\"", "\"subject\":\"twice\",\"harness\"");

        given().contentType("application/json").body(request)
                .when().post("/api/runs")
                .then().statusCode(201);
        given().contentType("application/json").body(request)
                .when().post("/api/runs")
                .then().statusCode(409)
                .body(containsString("run::github:" + workspace + "/app:twice:1"))
                .body(containsString(FactoryRunProjection.QUEUED));
    }

    @Inject
    AppSettingRepository settings;

    @Inject
    SpendWindow spendWindow;

    @Inject
    CapPolicy capPolicy;

    @Inject
    ReviewProjection reviews;

    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void aRunIsRefusedUnderTheDeploymentsSpendCapAndLeavesNoRow() {
        // A run is a paid model call and V40 counts its spend in the same rolling window as reviews,
        // so the ADR-025 gate must refuse it too — before the queued row, so a refusal is not a run
        // the operator then has to explain. The cap is set to what the shared ledger already holds
        // plus the one call seeded here, because a stored "0" reads as UNSET by design (it would stop
        // a deployment forever). Cleared in finally so the cap cannot leak into the other tests.
        String workspace = workspaceWithFactoryAccount();
        String request = body(workspace).replace("\"harness\"", "\"subject\":\"capped\",\"harness\"");
        SpendWindow.Usage baseline = spendWindow.since(Instant.now().minus(capPolicy.window()))
                .orElseThrow(() -> new AssertionError("the ledger read failed"));
        reviews.recordCharges(new ChargeCall("TEST-run-cap-" + workspace, "CANARY-RUN-CAP-" + workspace,
                ChargeKind.REVIEW, MODEL, List.of(ChargeLine.unmetered(TokenType.INPUT, 1_000))));
        settings.set(CapPolicy.KEY_CALLS, String.valueOf(baseline.calls() + 1));
        try {
            given().contentType("application/json").body(request)
                    .when().post("/api/runs")
                    .then().statusCode(429)
                    .body(containsString("call cap reached"));
            given().when().get("/api/runs/run::github:" + workspace + "/app:capped:1")
                    .then().statusCode(404);
        } finally {
            settings.set(CapPolicy.KEY_CALLS, "");
        }
        given().contentType("application/json").body(request)
                .when().post("/api/runs")
                .then().statusCode(201);
    }

    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void aNestedGitLabNamespaceIsDispatchable() {
        // GitLab nests groups, so a workspace is `group/subgroup`; RunIds splits on the LAST slash.
        // Validating the whole workspace as one segment refused every nested namespace at the door
        // while the GET route's own comment said they were supported.
        defaultLlmProvider();
        String workspace = "TEST-grp-" + UUID.randomUUID().toString().substring(0, 8) + "/team";
        providers.create(new ProviderInput("factory-bot", "gitlab", "https://gitlab.com", workspace,
                "bearer", null, "TEST-factory-token", "", true, List.of(), "factory-bot", null, "FACTORY"));
        String request = body(workspace).replace("\"providerType\":\"github\"", "\"providerType\":\"gitlab\"");

        given().contentType("application/json").body(request)
                .when().post("/api/runs")
                .then().statusCode(201)
                .body("runId", startsWith("run::gitlab:" + workspace + "/app:"));

        // Still one segment per part: a leading, trailing or empty segment is refused.
        for (String bad : List.of("/grp", "grp/", "grp//team", "grp/../team")) {
            given().contentType("application/json")
                    .body(request.replace("\"workspace\":\"" + workspace + "\"", "\"workspace\":\"" + bad + "\""))
                    .when().post("/api/runs")
                    .then().statusCode(400);
        }
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
        // The stored detail is a fixed sentence: the row is viewer-readable, and the broker's own
        // message names hosts and internals. That text reaches the admin's 503 and the log only.
        given().when().get("/api/runs/" + runId)
                .then().statusCode(200)
                .body("status", equalTo(FactoryRunProjection.FAILED))
                .body("failureCause", equalTo(FactoryRunProjection.DISPATCH_FAILED))
                .body("failureDetail", equalTo(RunResource.DISPATCH_FAILED_DETAIL));

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
    @TestSecurity(user = "op", roles = "spire-admin")
    void anUnboundedOrFlagShapedInputIsRefusedAtTheDoor() {
        // prompt: rides every command copy and the DLQ row. model: reaches the harness argv as
        // `--model <value>`. baseBranch: the publisher clones it, so git's own refusals are applied
        // here rather than by an init container that fails after the row is written.
        String workspace = workspaceWithFactoryAccount();
        String ok = body(workspace);

        given().contentType("application/json")
                .body(ok.replace("\"fix the typo\"", "\"" + "x".repeat(64 * 1024 + 1) + "\""))
                .when().post("/api/runs").then().statusCode(400).body(containsString("prompt"));
        given().contentType("application/json")
                .body(ok.replace("\"gpt-5.6\"", "\"-c model_providers.openai.base_url=http://attacker.example/v1\""))
                .when().post("/api/runs").then().statusCode(400).body(containsString("model"));
        for (String bad : List.of("..main", "/main", "main/", "a//b", "feature/.hidden", "-x", "x.lock", "a b")) {
            given().contentType("application/json")
                    .body(ok.replace("\"harness\"", "\"baseBranch\":\"" + bad + "\",\"harness\""))
                    .when().post("/api/runs").then().statusCode(400).body(containsString("baseBranch"));
        }
        // A nested, dotted branch name git accepts is accepted here too.
        given().contentType("application/json")
                .body(ok.replace("\"harness\"", "\"subject\":\"nested-base\",\"baseBranch\":\"release/1.2\",\"harness\""))
                .when().post("/api/runs").then().statusCode(201);
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
