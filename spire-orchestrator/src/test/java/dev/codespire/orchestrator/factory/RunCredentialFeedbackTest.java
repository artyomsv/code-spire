package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a run's outcome teaches the pool, and what the pool's state tells an operator (FR-F12).
 *
 * <p>The pool cannot learn its own health: the orchestrator hands a key to a sandbox and never calls
 * the model, so the only signal is the run's classified failure coming back. This is the seam that
 * translates one into the other, and the seam is exactly where this project keeps finding that a
 * feature was installed and inert.
 */
@QuarkusTest
@TestSecurity(user = "op", roles = {"spire-viewer", "spire-admin"})
class RunCredentialFeedbackTest {

    @Inject
    HarnessCredentialPool pool;

    @Inject
    FactoryRunProjection projection;

    @Inject
    RunCredentialFeedback feedback;

    @Inject
    RunCharges charges;

    @Inject
    RunResultSaga saga;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void onlyOurMembers() {
        sql("UPDATE harness_credential SET enabled = FALSE");
    }

    private UUID member(String suffix) {
        return pool.add("TEST-pool-" + suffix + "-" + UUID.randomUUID(), "openai",
                "https://api.openai.com", "TEST-agent-key").id();
    }

    private String runOn(UUID credential) {
        String runId = "run::github:TEST-acme/app:cred-" + UUID.randomUUID() + ":1";
        projection.queued(new FactoryRunProjection.QueuedRun(runId, "codex", "TEST-RUN-MODEL", "main",
                "abc1234", "spire/x", "spire-bot", credential));
        return runId;
    }

    /**
     * A refusal takes the key that was refused out of rotation — and only that one.
     *
     * <p>The run names its own member on its row, so the pool marks the key that actually failed
     * rather than whichever one it would hand out next. Marking by selection instead would take a
     * healthy key out on the strength of a run that never used it.
     */
    @Test
    void aRefusedCredentialLeavesTheRotationAndItsNeighbourDoesNot() {
        UUID refused = member("refused");
        UUID healthy = member("healthy");
        String runId = runOn(refused);

        feedback.reactTo(new RunResult.RunFailed(runId, "CREDENTIAL_REJECTED",
                "the provider refused the key", false, null));

        assertEquals(healthy, assertChosen(), "the refused member is out; its neighbour still serves");
        assertTrue(view(refused).rejectedAt() != null);
        assertNull(view(healthy).rejectedAt());
    }

    /**
     * A provider outage marks NOTHING, and that restraint is the decision.
     *
     * <p>{@code MODEL_UNAVAILABLE} covers an outage as well as a rate limit and cannot tell them
     * apart. Treating it as exhaustion would rest a perfectly good key on every blip — and with a
     * small pool, one outage would rest every member at once, turning a transient fault into a
     * refusal quoting a recovery time nobody can rely on.
     */
    @Test
    void aProviderOutageDoesNotTakeAKeyOutOfRotation() {
        UUID only = member("only");
        String runId = runOn(only);

        feedback.reactTo(new RunResult.RunFailed(runId, "MODEL_UNAVAILABLE", "502 from the provider",
                true, null));

        assertNull(view(only).rejectedAt());
        assertNull(view(only).rateLimitedUntil());
        assertEquals(only, assertChosen(), "the key is still handed out, because nothing proved it bad");
    }

    /**
     * The wiring, not the translation.
     *
     * <p>Every other test here calls {@code feedback.reactTo} directly, so deleting the saga's call
     * to it left the whole suite green while the feature did nothing — the installed-and-inert seam
     * this class's own javadoc names as the thing this project keeps rediscovering, and then did not
     * assert.
     */
    @Test
    void aRefusalReachesThePoolThroughTheSagaAndNotOnlyByADirectCall() {
        UUID refused = member("refused");
        String runId = runOn(refused);

        saga.on(new RunResult.RunFailed(runId, "CREDENTIAL_REJECTED", "the provider refused", false, null));

        assertTrue(view(refused).rejectedAt() != null,
                "the saga must reach the feedback path; a direct call proves the translation, not the"
                        + " wiring, and the wiring is what a deleted line removes");
    }

    @Test
    void aRunThatNamesNoMemberMarksNothing() {
        // A run dispatched before the pool existed. Marking an arbitrary member on its behalf would
        // take a working key out of rotation on no evidence at all.
        UUID healthy = member("healthy");
        String runId = runOn(null);

        feedback.reactTo(new RunResult.RunFailed(runId, "CREDENTIAL_REJECTED", "refused", false, null));

        assertNull(view(healthy).rejectedAt());
    }

    @Test
    void anOrdinaryAgentFailureMarksNothing() {
        // The half that keeps the marking from being unconditional: most runs fail for reasons that
        // say nothing whatever about the key.
        UUID only = member("only");
        String runId = runOn(only);

        feedback.reactTo(new RunResult.RunFailed(runId, "AGENT_FAILED", "exit 1", false, null));

        assertNull(view(only).rejectedAt());
    }

    /**
     * A run's charges name the key that paid for them (FR-F12, closing V42's unwritten column).
     *
     * <p>On an UNMETERED deployment every run charge is an asserted zero, so "which key spent this"
     * is unanswerable from the money column — two keys that both cost nothing are identical there.
     * The attribution is the only thing that distinguishes them.
     */
    @Test
    void aRunsChargeNamesTheCredentialThatPaidForIt() {
        UUID credential = member("payer");
        String runId = runOn(credential);

        charges.record(new RunResult.RunFinished(runId, "refs/heads/spire/x", java.util.List.of("a.txt"),
                java.util.List.of(), Map.of("INPUT", 100L, "OUTPUT", 50L), false));

        assertEquals(credential.toString(), credentialRefOf(runId),
                "V42 added this column for exactly this and nothing had ever written it");
    }

    @Test
    void aRunThatNamesNoMemberIsChargedWithoutAnAttribution() {
        // Renamed: this drives the RUN path with a null member, not ChargeCall.forReview. The old
        // name asserted coverage of a path this test never touches -- and a review charge genuinely
        // has no pool member, which is the reviewer/factory separation working.
        String runId = runOn(null);

        charges.record(new RunResult.RunFinished(runId, null, java.util.List.of(), java.util.List.of(),
                Map.of("INPUT", 10L), false));

        assertNull(credentialRefOf(runId));
    }

    /**
     * Rotating to another member does not charge the first run again.
     *
     * <p>M1 exit criterion 3, asserted on the ledger rather than on the pool. Each run keys its own
     * charge, so a second run drawing a different member writes its own row and leaves the first
     * one's spend exactly as it was — the property that makes a rotating pool safe to put in front of
     * a money ledger.
     */
    @Test
    void rotationDoesNotRechargeTheCall() {
        UUID first = member("first");
        String firstRun = runOn(first);
        charges.record(new RunResult.RunFinished(firstRun, null, java.util.List.of(), java.util.List.of(),
                Map.of("INPUT", 100L), false));
        int firstRows = chargeRowsFor(firstRun);

        pool.markRejected(first);
        UUID second = member("second");
        String secondRun = runOn(second);
        charges.record(new RunResult.RunFinished(secondRun, null, java.util.List.of(), java.util.List.of(),
                Map.of("INPUT", 100L), false));

        assertEquals(firstRows, chargeRowsFor(firstRun), "the first run's spend is untouched");
        assertEquals(second.toString(), credentialRefOf(secondRun));
        assertEquals(first.toString(), credentialRefOf(firstRun),
                "and stays attributed to the key that actually paid, even after it was refused");
    }

    /**
     * The panel says no run can start, and says which half of the pool is the reason.
     *
     * <p>BLOCKING rather than WARNING on the same reasoning the spend cap uses: severity describes
     * impact, not fault, and while this holds every factory run is refused.
     */
    @Test
    void anExhaustedPoolRaisesABlockingRowNamingWhyAndClearsOnRecovery() {
        UUID dead = member("dead");
        pool.markRejected(dead);

        given().when().get("/api/attention")
                .then().statusCode(200)
                .body("code", hasItem("HARNESS_POOL_EXHAUSTED"))
                .body("find { it.code == 'HARNESS_POOL_EXHAUSTED' }.severity", containsString("BLOCKING"))
                .body("find { it.code == 'HARNESS_POOL_EXHAUSTED' }.message",
                        containsString("Nothing recovers on its own"));

        // Fixing the cause removes the row, which is the panel's whole contract.
        assertTrue(pool.clearRejection(dead));
        given().when().get("/api/attention")
                .then().statusCode(200)
                .body("code", not(hasItem("HARNESS_POOL_EXHAUSTED")));
    }

    @Test
    void aPoolWithOneWorkingMemberRaisesNothing() {
        // The half that keeps the row from firing on a pool that is merely under strain: three dead
        // keys and one live one still starts every run, and warning about a working system is the
        // wallpaper this panel excludes.
        UUID dead = member("dead");
        member("alive");
        pool.markRejected(dead);

        given().when().get("/api/attention")
                .then().statusCode(200)
                .body("code", not(hasItem("HARNESS_POOL_EXHAUSTED")));
    }

    @Test
    void aDeploymentWithNoPoolAtAllIsNotNagged() {
        // A deployment that never runs the factory should not be told about a feature it does not
        // use; the dispatch refusal already names what to configure to whoever actually tried.
        given().when().get("/api/attention")
                .then().statusCode(200)
                .body("code", not(hasItem("HARNESS_POOL_EXHAUSTED")));
    }

    private UUID assertChosen() {
        return ((HarnessCredentialPool.Selection.Chosen) pool.select()).member().id();
    }

    private HarnessCredentialPool.MemberView view(UUID id) {
        return pool.list().stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
    }

    private String credentialRefOf(String runId) {
        return one("SELECT credential_ref FROM llm_charge WHERE subject_id = '" + runId + "' LIMIT 1");
    }

    private int chargeRowsFor(String runId) {
        return Integer.parseInt(one("SELECT count(*) FROM llm_charge WHERE subject_id = '" + runId + "'"));
    }

    private String one(String query) {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery(query)) {
            return rs.next() ? rs.getString(1) : null;
        } catch (SQLException e) {
            throw new IllegalStateException("read failed: " + query, e);
        }
    }

    private void sql(String statement) {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + statement, e);
        }
    }
}
