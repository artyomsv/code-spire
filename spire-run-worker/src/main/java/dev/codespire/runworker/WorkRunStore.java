package dev.codespire.runworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunResult;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.runtime.RunUnitSpec;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Encrypted execution, topology and two-result outbox. Every paid or publishing claim precedes IO. */
@ApplicationScoped
public class WorkRunStore {
    @Inject DataSource dataSource;
    @Inject ObjectMapper mapper;
    @Inject EncryptionService encryption;
    @Inject WorkspaceLeases leases;

    public record Held(RunCommand.ExecuteWorkRun execution, RunUnitSpec unit, String unitId,
                       String state, RunResult.RunWorkReady ready, RunResult terminal,
                       RunCommand.PublishWorkRun permit, java.time.Instant updatedAt) {}

    public boolean claim(RunCommand.ExecuteWorkRun execution) {
        byte[] payload = encode(execution.runId(), "execution", execution);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement claim = c.prepareStatement("""
                    INSERT INTO runworker.run_claim(run_id,slot) VALUES (?,'execute')
                    ON CONFLICT DO NOTHING
                    """)) {
                claim.setString(1, execution.runId());
                if (claim.executeUpdate() == 0) { c.rollback(); return false; }
            }
            try (PreparedStatement insert = c.prepareStatement("""
                    INSERT INTO runworker.work_run(run_id,binding,execution,state) VALUES (?,?,?,'building')
                    """)) {
                insert.setString(1, execution.runId());
                insert.setString(2, execution.work().publicationKey());
                insert.setBytes(3, payload);
                insert.executeUpdate();
            }
            c.commit();
            return true;
        } catch (SQLException e) { throw database(e); }
    }

    public void saveUnit(String runId, RunUnitSpec unit) {
        if (!runId.equals(unit.runId())) throw new IllegalArgumentException("The retained unit names another run");
        if (update("""
                UPDATE runworker.work_run SET unit_spec=?,updated_at=now()
                WHERE run_id=? AND state='building' AND unit_spec IS NULL
                """, encode(runId, "unit", unit), runId) != 1) {
            throw new IllegalStateException("The build topology has already been recorded or its claim is missing");
        }
    }

    public void recordUnit(String runId, String unitId) {
        update("UPDATE runworker.work_run SET unit_id=?,updated_at=now() WHERE run_id=? AND state='building'", unitId, runId);
    }

    public Optional<Held> find(String runId) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT * FROM runworker.work_run WHERE run_id=?")) {
            ps.setString(1, runId);
            try (ResultSet row = ps.executeQuery()) { return row.next() ? Optional.of(read(row)) : Optional.empty(); }
        } catch (SQLException e) { throw database(e); }
    }

    public void buildResult(RunResult result) {
        if (result instanceof RunResult.RunWorkReady ready) {
            if (update("""
                    UPDATE runworker.work_run SET ready_result=?,state='ready',updated_at=now()
                    WHERE run_id=? AND binding=? AND state='building' AND unit_spec IS NOT NULL
                    """, encode(result.runId(), "ready", ready), result.runId(), ready.work().publicationKey()) != 1) {
                throw new IllegalStateException("Ready evidence does not match an unfinished retained build");
            }
        } else {
            terminal(result);
        }
    }

    public void terminal(RunResult result) {
        if (!(result instanceof RunResult.RunFinished || result instanceof RunResult.RunFailed))
            throw new IllegalArgumentException("A terminal result is required");
        update("""
                UPDATE runworker.work_run SET final_result=?,state='finished',release_pending=?,updated_at=now()
                WHERE run_id=? AND final_result IS NULL
                """, encode(result.runId(), "final", result),
                result instanceof RunResult.RunFinished finished && finished.pushedRef()!=null
                        && !finished.agentUnobserved(), result.runId());
    }

    /** The caller has already found the workspace on its daemon. Another daemon must never take this claim. */
    public boolean claimPublication(RunCommand.PublishWorkRun request) {
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            Held held;
            java.time.Instant now;
            try (PreparedStatement ps = c.prepareStatement("SELECT *,now() AS db_now FROM runworker.work_run WHERE run_id=? FOR UPDATE")) {
                ps.setString(1, request.runId());
                try (ResultSet row = ps.executeQuery()) {
                    if (!row.next()) { c.rollback(); return false; }
                    held = read(row);
                    now = row.getTimestamp("db_now").toInstant();
                }
            }
            if (!"ready".equals(held.state()) || held.ready() == null
                    || !held.execution().work().equals(request.permit().work())
                    || !held.ready().head().equals(request.permit().head())
                    || !request.permit().validAt(now)) {
                c.rollback();
                return false;
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE runworker.work_run SET state='publishing',permit=?,permit_id=?,updated_at=now()
                    WHERE run_id=? AND NOT EXISTS (SELECT 1 FROM runworker.run_claim WHERE run_id=? AND slot='cancel')
                    """)) {
                ps.setBytes(1, encode(request.runId(), "permit", request));
                ps.setObject(2, request.permit().deliveryAttemptId());
                ps.setString(3, request.runId());
                ps.setString(4, request.runId());
                if (ps.executeUpdate() != 1) { c.rollback(); return false; }
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO runworker.run_lease(run_id,owner_id,heartbeat_at,unit_id)
                    VALUES (?,?,now(),?) ON CONFLICT(run_id) DO UPDATE
                    SET owner_id=EXCLUDED.owner_id,heartbeat_at=now(),unit_id=EXCLUDED.unit_id,preserved_at=NULL
                    """)) {
                ps.setString(1, request.runId()); ps.setString(2, leases.ownerId()); ps.setString(3, held.unitId());
                ps.executeUpdate();
            }
            c.commit();
            return true;
        } catch (SQLException e) { throw database(e); }
    }

    public List<Held> unfinished() {
        List<Held> held = new ArrayList<>();
        try (Connection c=dataSource.getConnection(); PreparedStatement ps=c.prepareStatement(
                """
                SELECT * FROM runworker.work_run WHERE state IN ('building','publishing')
                    OR (state='ready' AND EXISTS (SELECT 1 FROM runworker.run_claim c
                        WHERE c.run_id=work_run.run_id AND c.slot='cancel'))
                ORDER BY updated_at LIMIT 20
                """);
             ResultSet rows=ps.executeQuery()) {
            while(rows.next())held.add(read(rows));
        } catch(SQLException e){throw database(e);}
        return List.copyOf(held);
    }

    public List<Held> awaitingRelease() {
        List<Held> held = new ArrayList<>();
        try(Connection c=dataSource.getConnection(); PreparedStatement ps=c.prepareStatement(
                "SELECT * FROM runworker.work_run WHERE release_pending ORDER BY updated_at LIMIT 20");ResultSet rows=ps.executeQuery()) {
            while(rows.next())held.add(read(rows));
        }catch(SQLException e){throw database(e);}
        return List.copyOf(held);
    }

    public void released(String runId) {
        update("UPDATE runworker.work_run SET release_pending=false WHERE run_id=? AND final_result IS NOT NULL",runId);
    }

    /** Only a stale/preserved owner can be replaced; the claimed publisher itself is never recreated. */
    public boolean claimPublicationRecovery(String runId, java.time.Instant staleBefore) {
        return update("""
                INSERT INTO runworker.run_lease(run_id,owner_id,heartbeat_at,unit_id)
                SELECT run_id,?,now(),unit_id FROM runworker.work_run WHERE run_id=? AND state='publishing'
                ON CONFLICT(run_id) DO UPDATE SET owner_id=EXCLUDED.owner_id,heartbeat_at=now(),
                    unit_id=EXCLUDED.unit_id,preserved_at=NULL
                WHERE runworker.run_lease.preserved_at IS NOT NULL OR runworker.run_lease.heartbeat_at < ?
                """, leases.ownerId(),runId,Timestamp.from(staleBefore))==1;
    }

    public void abandonBuild(RunResult.RunFailed failure) {
        update("""
                UPDATE runworker.work_run SET final_result=?,state='finished',updated_at=now()
                WHERE run_id=? AND state='building'
                """,encode(failure.runId(),"final",failure),failure.runId());
    }

    public List<RunResult> pendingResults() {
        List<RunResult> results = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                SELECT run_id,ready_result,final_result,ready_sent_at,final_sent_at FROM runworker.work_run
                WHERE (ready_result IS NOT NULL AND ready_sent_at IS NULL)
                   OR (final_result IS NOT NULL AND final_sent_at IS NULL)
                ORDER BY updated_at LIMIT 20
                """); ResultSet rows = ps.executeQuery()) {
            while (rows.next()) {
                String id = rows.getString("run_id");
                if (rows.getTimestamp("ready_sent_at") == null && rows.getBytes("ready_result") != null)
                    results.add(decode(id, "ready", rows.getBytes("ready_result"), RunResult.class));
                if (rows.getTimestamp("final_sent_at") == null && rows.getBytes("final_result") != null)
                    results.add(decode(id, "final", rows.getBytes("final_result"), RunResult.class));
            }
        } catch (SQLException e) { throw database(e); }
        return List.copyOf(results);
    }

    public void acknowledged(RunResult result) {
        if (result instanceof RunResult.RunWorkReady) {
            update("UPDATE runworker.work_run SET ready_sent_at=now() WHERE run_id=? AND ready_result IS NOT NULL", result.runId());
        } else {
            update("UPDATE runworker.work_run SET final_sent_at=now() WHERE run_id=? AND final_result IS NOT NULL", result.runId());
        }
    }

    private Held read(ResultSet row) throws SQLException {
        String id = row.getString("run_id");
        return new Held(decode(id, "execution", row.getBytes("execution"), RunCommand.ExecuteWorkRun.class),
                decode(id, "unit", row.getBytes("unit_spec"), RunUnitSpec.class), row.getString("unit_id"), row.getString("state"),
                decode(id, "ready", row.getBytes("ready_result"), RunResult.RunWorkReady.class),
                decode(id, "final", row.getBytes("final_result"), RunResult.class),
                decode(id, "permit", row.getBytes("permit"), RunCommand.PublishWorkRun.class),row.getTimestamp("updated_at").toInstant());
    }

    private byte[] encode(String id, String slot, Object value) {
        try { return encryption.encrypt(mapper.writeValueAsBytes(value), "held-work:" + id + ":" + slot); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot encode retained work", e); }
    }

    private <T> T decode(String id, String slot, byte[] value, Class<T> type) {
        if (value == null) return null;
        try { return mapper.readValue(encryption.decrypt(value, "held-work:" + id + ":" + slot), type); }
        catch (java.io.IOException e) { throw new IllegalStateException("Cannot decode retained work", e); }
    }

    private int update(String sql, Object... args) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i=0;i<args.length;i++) ps.setObject(i+1,args[i]);
            return ps.executeUpdate();
        } catch (SQLException e) { throw database(e); }
    }

    private static IllegalStateException database(SQLException e) {
        return new IllegalStateException("Retained work could not be recorded", e);
    }
}
