package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.work.PayWith;
import dev.codespire.contract.work.WorkDraftBinding;
import dev.codespire.contract.work.WorkDraftLifecycle;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.orchestrator.factory.FactoryRunProjection;
import dev.codespire.orchestrator.factory.RunCharges;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The durable inbox of specification and plan run results (M4 slice 3), the pattern of {@link WorkItemRunBridge}: a
 * result is stored encrypted on its {@code work_prepare_effect} row first, then applied under the item lock, and a
 * scheduled {@link #recover} applies every stored result a crash left unapplied. A redelivered result finds its payload
 * already stored and applied, so it changes nothing.
 *
 * <p>Wired from {@code RunResultSaga}: {@link #acceptsBinding} runs before the projection and the charge, and
 * {@link #accept} claims every result of a draft run, so neither a draft artifact nor a draft failure reaches the
 * work-run bridge or a pull request proposal.
 */
@ApplicationScoped
public class WorkDraftResults {
    @Inject DataSource dataSource;
    @Inject ObjectMapper mapper;
    @Inject EncryptionService encryption;
    @Inject FactoryRunProjection runs;
    @Inject WorkDraftTransitions drafts;

    /** The effect row a stored result is applied from. */
    private record Row(String itemId, UUID attempt, WorkDraftBinding.Kind kind, RunResult result, UUID retryOf, long setupRevision) {}

    private static String aad(String runId) { return "work-prepare-result:" + runId; }

    /**
     * An artifact is accepted only for the effect row that dispatched it: its item, generation, attempt, kind and
     * provisional digest. Anything else is refused {@code draft_binding_mismatch} before it can be projected or charged.
     */
    public boolean acceptsBinding(RunResult result) {
        if (!(result instanceof RunResult.RunWorkArtifact artifact)) return true;
        WorkDraftBinding bound = artifact.draft();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                SELECT 1 FROM work_prepare_effect WHERE run_id=? AND work_item_id=? AND generation=? AND attempt_id=? AND kind=?
                   AND provisional_digest=?
                """)) {
            ps.setString(1, artifact.runId()); ps.setString(2, bound.workItemId()); ps.setLong(3, bound.generation());
            ps.setObject(4, bound.attemptId()); ps.setString(5, bound.kind().name()); ps.setString(6, bound.provisionalDigest());
            try (ResultSet rs = ps.executeQuery()) { if (rs.next()) return true; }
            try (PreparedStatement note = c.prepareStatement("UPDATE work_prepare_effect SET reason='draft_binding_mismatch',updated_at=now() WHERE run_id=?")) {
                note.setString(1, artifact.runId());
                note.executeUpdate();
            }
            return false;
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }

    /** @return whether this result belongs to a draft run, which nothing after this may handle */
    public boolean accept(RunResult result) {
        if (!draftRun(result.runId())) return false;
        if (!(result instanceof RunResult.RunWorkArtifact || result instanceof RunResult.RunFailed)) return true;
        if (!acceptsBinding(result)) return true;
        store(result);
        apply(result.runId());
        return true;
    }

    /** Stores the terminal result on its row, once: a redelivery keeps the payload first stored. */
    boolean store(RunResult result) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                UPDATE work_prepare_effect SET state=CASE WHEN state IN ('pending','uncertain') THEN 'sent' ELSE state END,
                       result_payload=COALESCE(result_payload,?),updated_at=now() WHERE run_id=?
                """)) {
            ps.setBytes(1, encryption.encrypt(mapper.writeValueAsBytes(result), aad(result.runId())));
            ps.setString(2, result.runId());
            return ps.executeUpdate() == 1;
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Cannot encode a draft run result", failure); }
    }

    @Scheduled(every = "${spire.work-prepare-interval:5s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void recover() {
        List<String> ids = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT run_id FROM work_prepare_effect WHERE result_payload IS NOT NULL AND NOT result_processed ORDER BY created_at LIMIT 20");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) ids.add(rs.getString(1));
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        for (String id : ids) apply(id);
    }

    /** Applies a stored, unprocessed result once; a result the item no longer waits for is refused and changes nothing. */
    void apply(String runId) {
        Row row = row(runId);
        if (row == null) return;
        FactoryRunProjection.RunView run = runs.find(runId).orElseThrow(() -> new IllegalStateException("Draft run " + runId + " has no row"));
        WorkDraftTransitions.DraftResult result = new WorkDraftTransitions.DraftResult(row.attempt(), row.kind(), row.result(),
                usage(row.result(), run), new WorkDraftTransitions.Origin(run.baseCommit(), row.setupRevision(), row.retryOf() != null));
        WorkItemTransitions.Outcome outcome = drafts.apply(row.itemId(), result);
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE work_prepare_effect SET result_processed=true,state=?,reason=?,updated_at=now() WHERE run_id=? AND NOT result_processed")) {
            ps.setString(1, outcome.status() == 200 ? "done" : "refused");
            ps.setString(2, outcome.status() == 200 ? null : outcome.reason());
            ps.setString(3, runId);
            ps.executeUpdate();
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }

    /**
     * What the run spent, as a build's usage is read (WorkItemRunBridge): a failure before the agent bought nothing, a
     * seat run spent nothing per token, and an unpriced run's cost is unknown, never an invented zero.
     */
    private WorkDraftLifecycle.RunUsage usage(RunResult result, FactoryRunProjection.RunView run) {
        long wall = result instanceof RunResult.RunWorkArtifact artifact ? artifact.activeWallSeconds()
                : run.startedAt() == null || run.endedAt() == null ? 0 : Math.max(0, Duration.between(run.startedAt(), run.endedAt()).toSeconds());
        if (RunCharges.nothingWasBought(result)) return new WorkDraftLifecycle.RunUsage(wall, 0, 0, true);
        if (PayWith.SUBSCRIPTION.equals(runs.paidByOf(result.runId()).orElse(null))) return new WorkDraftLifecycle.RunUsage(wall, 0, 1, true);
        boolean known = run.cost().isKnown();
        return new WorkDraftLifecycle.RunUsage(wall, known ? run.cost().millicents() : 0, 1, known);
    }

    private boolean draftRun(String runId) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT 1 FROM work_prepare_effect WHERE run_id=?")) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }

    private Row row(String runId) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                SELECT work_item_id,attempt_id,kind,result_payload,retry_of,setup_revision FROM work_prepare_effect
                 WHERE run_id=? AND result_payload IS NOT NULL AND NOT result_processed
                """)) {
            ps.setString(1, runId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                RunResult result = mapper.readValue(encryption.decrypt(rs.getBytes(4), aad(runId)), RunResult.class);
                return new Row(rs.getString(1), rs.getObject(2, UUID.class), WorkDraftBinding.Kind.valueOf(rs.getString(3)), result,
                        rs.getObject(5, UUID.class), rs.getLong(6));
            }
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Cannot decode a draft run result", failure); }
    }
}
