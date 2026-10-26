package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.work.StepSummary;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;

/**
 * Each build step's summary (M4 slice 8, §6.8, V100): what the agent said it decided, rejected and changed from the
 * plan, stored when the run's ready result is applied. The agent wrote it, so it is encrypted under an AAD naming the
 * item, generation, step and run, and never logged. A ready without one records {@code summary_missing}, so the steps
 * view can say so; the step still completes. The key makes a redelivered ready insert nothing. The step index is the
 * one the run was dispatched for ({@code factory_run.step_index}, slice 9), 0 for the build of an item without a plan
 * cursor; a land fix after a plan records none.
 */
@ApplicationScoped
public class WorkStepSummaries {
    static final String RECORDED = "recorded", MISSING = "summary_missing";

    @Inject DataSource dataSource;
    @Inject ObjectMapper mapper;
    @Inject EncryptionService encryption;

    /** One step's newest summary; {@code summary} is null exactly when the state is {@code summary_missing}. */
    public record Step(int stepIndex, String runId, String state, StepSummary summary) {}

    /** A row's identity, which its ciphertext is bound to: a summary moved to another row does not decrypt. */
    private record Key(String workItemId, long generation, int stepIndex, String runId) {
        String aad() {
            return "work-step-summary:" + workItemId + ":" + generation + ":" + stepIndex + ":" + runId;
        }
    }

    /** Records the ready's summary for this step, or that it had none; a second call for the same run is a no-op. */
    void record(Connection c, RunResult.RunWorkReady ready, int stepIndex) throws SQLException {
        Key key = new Key(ready.work().workItemId(), ready.work().generation(), stepIndex, ready.runId());
        StepSummary summary = ready.stepSummary();
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO work_step_summary(work_item_id,generation,step_index,run_id,summary,state) VALUES (?,?,?,?,?,?)
                ON CONFLICT (work_item_id,generation,step_index,run_id) DO NOTHING
                """)) {
            ps.setString(1, key.workItemId());
            ps.setLong(2, key.generation());
            ps.setInt(3, key.stepIndex());
            ps.setString(4, key.runId());
            ps.setBytes(5, summary == null ? null : encrypt(summary, key.aad()));
            ps.setString(6, summary == null ? MISSING : RECORDED);
            ps.executeUpdate();
        }
    }

    /**
     * Records the ready's summary under the step its run built: 0 on an item without a plan cursor. A build of a
     * plan's item that is no step of it, a land fix, records nothing: under 0 it stood in for step 1's summary, since
     * the steps view shows each step's newest row.
     */
    void recordBuilt(Connection c, RunResult.RunWorkReady ready, WorkItemEvent item) throws SQLException {
        Integer step = stepIndexOf(c, ready.runId());
        if (step == null && item.steps() != null) return;
        record(c, ready, step == null ? 0 : step);
    }

    /**
     * Whether a completed step of the item's current plan has a ready the bridge has not acknowledged yet (review of
     * PR 287, M-1). The bridge stores a step's summary with that acknowledgement, after the transition that may already
     * have made the next step's build pending, so a step dispatched in between would be told for good that its
     * predecessor decided nothing. The ready of a completed step completed it, so its replay always acknowledges.
     */
    static boolean unacknowledgedStep(Connection c, WorkItemEvent item) throws SQLException {
        if (!WorkStepAssembly.buildsStep(item) || item.steps().completed().isEmpty()) return false;
        String[] runs = item.steps().completed().stream().map(step -> step.execution().runId()).toArray(String[]::new);
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT 1 FROM work_run_effect WHERE work_item_id=? AND generation=? AND run_id = ANY(?)
                   AND ready_payload IS NOT NULL AND NOT ready_processed
                """)) {
            ps.setString(1, item.workItemId());
            ps.setLong(2, item.generation());
            ps.setArray(3, c.createArrayOf("text", runs));
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    /** The plan step a run was dispatched for; null for a build that is not a plan step. */
    static Integer stepIndexOf(Connection c, String runId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT step_index FROM factory_run WHERE run_id=?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                int step = rs.getInt(1);
                return rs.wasNull() ? null : step;
            }
        }
    }

    /** The newest run's summary of each step of this generation, in step order, decrypted. */
    public List<Step> list(String workItemId, long generation) {
        try (Connection c = dataSource.getConnection()) {
            return list(c, workItemId, generation);
        } catch (SQLException failure) {
            throw WorkSourceRegistry.database(failure);
        }
    }

    /** The same list on the caller's connection, which a step's build assembly holds the item lock on. */
    List<Step> list(Connection c, String workItemId, long generation) throws SQLException {
        List<Step> steps = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT DISTINCT ON (step_index) step_index,run_id,state,summary FROM work_step_summary
                WHERE work_item_id=? AND generation=? ORDER BY step_index, created_at DESC, run_id DESC
                """)) {
            ps.setString(1, workItemId);
            ps.setLong(2, generation);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) steps.add(step(rs, workItemId, generation));
            }
        }
        return steps;
    }

    private Step step(ResultSet rs, String workItemId, long generation) throws SQLException {
        Key key = new Key(workItemId, generation, rs.getInt(1), rs.getString(2));
        byte[] stored = rs.getBytes(4);
        StepSummary summary = stored == null ? null : decrypt(stored, key.aad());
        return new Step(key.stepIndex(), key.runId(), rs.getString(3), summary);
    }

    private byte[] encrypt(StepSummary summary, String aad) {
        try {
            return encryption.encrypt(mapper.writeValueAsBytes(summary), aad);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot encode a step summary", failure);
        }
    }

    private StepSummary decrypt(byte[] stored, String aad) {
        try {
            return mapper.readValue(encryption.decrypt(stored, aad), StepSummary.class);
        } catch (IOException failure) {
            throw new IllegalStateException("Cannot decode a stored step summary", failure);
        }
    }
}
