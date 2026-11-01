package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunWorkArtifact;
import dev.codespire.contract.work.WorkDraftBinding;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A specification or plan run's artifact (M4 slice 3): the run row finishes with nothing pushed, and its spend is
 * charged under the run's own kind. Through the real result consumer, an artifact no draft effect row dispatched is
 * neither projected nor charged (slice 6): the binding check runs before both.
 */
@QuarkusTest
class RunWorkArtifactProjectionTest {

    @Inject
    RunResultSaga saga;

    @Inject
    RunCharges charges;

    @Inject
    FactoryRunProjection projection;

    @Inject
    DataSource dataSource;

    @Test
    void anArtifactResultFinishesTheRun() {
        String runId = queuedSpecRun();
        RunResult artifact = artifact(runId);

        projection.apply(artifact);
        charges.record(artifact);

        assertEquals(Arrays.asList("succeeded", null, "4"), row(runId), "finished, with nothing pushed");
        assertEquals(List.of("SPEC"), chargeKinds(runId));
    }

    @Test
    void anArtifactNoDraftEffectDispatchedIsNeitherProjectedNorCharged() {
        String runId = queuedSpecRun();

        saga.on(artifact(runId));

        assertEquals("queued", row(runId).getFirst());
        assertEquals(List.of(), chargeKinds(runId));
    }

    private String queuedSpecRun() {
        String runId = "run::github:TEST-WS/TEST-REPO:TEST-spec-" + UUID.randomUUID() + ":1";
        assertTrue(projection.queued(new FactoryRunProjection.QueuedRun(runId, "codex", "TEST-MODEL", "main",
                "TESTSHA0", "spire/draft-" + UUID.randomUUID(), "TEST-bot", null).ofKind(RunKind.SPEC), null, null));
        return runId;
    }

    private static RunResult artifact(String runId) {
        return new RunWorkArtifact(runId, new WorkDraftBinding("TEST-item", 1, UUID.randomUUID(),
                WorkDraftBinding.Kind.SPEC, "d".repeat(64)), "TEST-specification", false, Map.of("INPUT", 10L), 4, List.of());
    }

    private List<String> row(String runId) {
        String sql = "SELECT status, pushed_ref, active_wall_seconds FROM factory_run WHERE run_id = ?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next(), "no row for " + runId);
                return Arrays.asList(rs.getString("status"), rs.getString("pushed_ref"),
                        rs.getString("active_wall_seconds"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read back the run row", e);
        }
    }

    private List<String> chargeKinds(String runId) {
        String sql = "SELECT DISTINCT kind FROM llm_charge WHERE subject_id = ? AND subject_kind = 'RUN'";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> kinds = new ArrayList<>();
                while (rs.next()) {
                    kinds.add(rs.getString("kind"));
                }
                return kinds;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read back the run's charge", e);
        }
    }
}
