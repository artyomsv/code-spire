package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ledger row a finished run writes names the run's own kind, read from {@code factory_run.kind}.
 *
 * <p>Through the real writer and the real V42 CHECK: a unit test of the {@code ChargeCall} would pass
 * while the INSERT refused SPEC, and the swallowed catch in {@link RunCharges} would lose the spend.
 */
@QuarkusTest
class RunChargesKindTest {

    @Inject
    RunCharges charges;

    @Inject
    FactoryRunProjection projection;

    @Inject
    DataSource dataSource;

    @Test
    void aSpecRunIsChargedAsSpecUnderBuild() {
        String runId = queue(base(runId("spec")).ofKind(RunKind.SPEC));

        charges.record(finished(runId));

        assertEquals(List.of("SPEC", "BUILD"), kindAndCapability(runId));
    }

    @Test
    void aPlanRunIsChargedAsPlanUnderBuild() {
        String runId = queue(base(runId("plan")).ofKind(RunKind.PLAN));

        charges.record(finished(runId));

        assertEquals(List.of("PLAN", "BUILD"), kindAndCapability(runId));
    }

    /** Review Focus 20: every fix run's ledger row used to say BUILD. */
    @Test
    void aFixRunIsChargedAsFix() {
        String runId = runId("fix");
        queue(base(runId).asFixFor("review::TEST-WS/TEST-REPO#7101", "TEST-thread-" + UUID.randomUUID(),
                "TEST-comment-" + UUID.randomUUID()));

        charges.record(finished(runId));

        assertEquals(List.of("FIX", "BUILD"), kindAndCapability(runId));
    }

    @Test
    void aBuildRunIsStillChargedAsBuild() {
        String runId = queue(base(runId("build")));

        charges.record(finished(runId));

        assertEquals(List.of("BUILD", "BUILD"), kindAndCapability(runId));
    }

    private static String runId(String what) {
        return "run::github:TEST-WS/TEST-REPO:TEST-" + what + "-" + UUID.randomUUID() + ":1";
    }

    private static FactoryRunProjection.QueuedRun base(String runId) {
        return new FactoryRunProjection.QueuedRun(runId, "codex", "TEST-MODEL", "main", "TESTSHA0",
                "spire/TEST-branch", "TEST-bot", null);
    }

    private String queue(FactoryRunProjection.QueuedRun row) {
        assertTrue(projection.queued(row, null, null), "the run's row must exist before it is charged");
        return row.runId();
    }

    private static RunResult.RunFinished finished(String runId) {
        return new RunResult.RunFinished(runId, "refs/heads/spire/TEST-branch", List.of(), List.of(),
                Map.of("INPUT", 10L), false, List.of());
    }

    /** kind and capability of the run's charge rows, which must agree across every token type. */
    private List<String> kindAndCapability(String runId) {
        String sql = "SELECT DISTINCT kind, capability FROM llm_charge WHERE subject_id = ? AND subject_kind = 'RUN'";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "the run's charge must exist before its kind can be judged");
                List<String> facts = List.of(rs.getString("kind"), rs.getString("capability"));
                assertTrue(!rs.next(), "one kind and one capability per run");
                return facts;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read back the run's charge", e);
        }
    }
}
