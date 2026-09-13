package dev.codespire.orchestrator.work;

import dev.codespire.worksource.*;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;

/** Durable coordinate pages; each reconciliation and checkpoint commit in the same transaction. */
@ApplicationScoped
public class WorkSourceScanner {
    @Inject DataSource dataSource;
    @Inject WorkSourceRegistry sources;
    @Inject WorkPolicyRegistry policies;
    @Inject WorkItemStore store;
    private record Candidate(UUID batch, int position, WorkIssueLocation issue) {}
    private static final int MAX_ITEMS_PER_SWEEP = 10;

    public void request(UUID source) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE work_source SET scan_requested=true WHERE id=?")) {
            ps.setObject(1, source);
            if (ps.executeUpdate() != 1) throw new IllegalArgumentException("Source is not registered");
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }

    @Scheduled(every="${spire.work-scan-interval:30s}", concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    public void sweep() {
        List<UUID> ids = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                SELECT id FROM work_source WHERE enabled AND (scan_requested OR checked_at IS NULL OR checked_at < now()-interval '5 minutes')
                ORDER BY checked_at NULLS FIRST,id LIMIT 1
                """); ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getObject(1, UUID.class)); }
        catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        ids.forEach(this::scan);
    }

    public boolean scan(UUID id) {
        WorkSourceRegistry.Source source = sources.get(id).orElseThrow();
        if (!source.enabled()) { sources.health(id, "source_unavailable"); return false; }
        long policyRevision = policies.get(source.repositoryId()).revision();
        if (pending(source) == null) {
            FutureTask<WorkPage<WorkIssueLocation>> task = new FutureTask<>(() -> sources.client(source).candidates(source.cursor()));
            Thread.ofVirtual().start(task);
            WorkPage<WorkIssueLocation> page;
            try { page = task.get(20, TimeUnit.SECONDS); }
            catch (Exception failure) {
                task.cancel(true);
                if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
                sources.health(id, "scan_unavailable"); return false;
            }
            if (page.items().size() > 100 || page.nextCursor() != null && page.nextCursor().equals(source.cursor())) {
                sources.health(id, "scan_unavailable"); return false;
            }
            if (!stage(source, policyRevision, page)) return false;
            if (page.items().isEmpty()) return true;
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);
        for (int count = 0; count < MAX_ITEMS_PER_SWEEP; count++) {
            Candidate candidate = pending(source);
            if (candidate == null) return true;
            if (Thread.currentThread().isInterrupted()) return false;
            WorkEvidence observation = WorkEvidence.collect(() -> sources.client(source), candidate.issue(), null);
            if (observation.failure() != null) { sources.health(id, "scan_unavailable"); return false; }
            if (!commit(source, policyRevision, candidate, observation)) return false;
            // Finish at a durable candidate boundary. One bounded observation may reach this deadline.
            if (System.nanoTime() >= deadline) return true;
        }
        return true;
    }

    private boolean stage(WorkSourceRegistry.Source source, long policyRevision, WorkPage<WorkIssueLocation> page) {
        return QuarkusTransaction.requiringNew().call(() -> {
            try (Connection c = dataSource.getConnection()) {
                if (!current(c, source, policyRevision)) return false;
                try (PreparedStatement ps = c.prepareStatement("SELECT scan_batch FROM work_source WHERE id=?")) {
                    ps.setObject(1, source.id()); try (ResultSet rs = ps.executeQuery()) {
                        rs.next(); if (rs.getObject(1) != null) return false;
                    }
                }
                if (page.items().isEmpty()) {
                    advance(c, source.id(), page.nextCursor()); return true;
                }
                UUID batch = UUID.randomUUID();
                try (PreparedStatement ps = c.prepareStatement("UPDATE work_source SET scan_batch=?,scan_next_cursor=?,scan_requested=true WHERE id=?")) {
                    ps.setObject(1,batch); ps.setString(2,page.nextCursor()); ps.setObject(3,source.id()); ps.executeUpdate();
                }
                int position = 0;
                for (WorkIssueLocation issue : page.items()) {
                    if (issue.ref().type() != source.type() || !issue.ref().origin().equals(source.origin())
                            || !issue.ref().projectId().equals(source.projectId())) throw new WorkSourceException("Candidate is outside the source.");
                    try (PreparedStatement ps = c.prepareStatement("INSERT INTO work_scan_candidate(source_id,batch_id,position,issue_id,issue_key,tracker_url) VALUES (?,?,?,?,?,?)")) {
                        ps.setObject(1,source.id()); ps.setObject(2,batch); ps.setInt(3,position++); ps.setString(4,issue.ref().issueId());
                        ps.setString(5,issue.issueKey()); ps.setString(6,issue.link().toString()); ps.executeUpdate();
                    }
                }
                return true;
            } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        });
    }

    private boolean commit(WorkSourceRegistry.Source source, long policyRevision, Candidate candidate, WorkEvidence evidence) {
        return QuarkusTransaction.requiringNew().call(() -> {
            try (Connection c = dataSource.getConnection()) {
                if (!current(c, source, policyRevision)) return false;
                Candidate current = pending(c, source);
                if (!candidate.equals(current)) return false;
                String delivery = "scan-" + candidate.batch() + "-" + candidate.position();
                if (store.reconcile(source, policyRevision, evidence, delivery) == null) throw new IllegalStateException("Scan authority changed");
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM work_scan_candidate WHERE source_id=? AND batch_id=? AND position=?")) {
                    ps.setObject(1,source.id()); ps.setObject(2,candidate.batch()); ps.setInt(3,candidate.position());
                    if (ps.executeUpdate() != 1) throw new IllegalStateException("Scan checkpoint changed");
                }
                if (pending(c,source) == null) {
                    try (PreparedStatement ps = c.prepareStatement("SELECT scan_next_cursor FROM work_source WHERE id=?")) {
                        ps.setObject(1,source.id()); try (ResultSet rs = ps.executeQuery()) { rs.next(); advance(c,source.id(),rs.getString(1)); }
                    }
                }
                return true;
            } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        });
    }
    private boolean current(Connection c, WorkSourceRegistry.Source source, long revision) throws SQLException {
        WorkSourceRegistry.Source current = sources.get(c, source.id(), true).orElseThrow();
        return current.enabled() && source.version().equals(current.version()) && Objects.equals(source.cursor(), current.cursor())
                && policies.get(c, source.repositoryId(), true).revision() == revision;
    }
    private Candidate pending(WorkSourceRegistry.Source source) {
        try (Connection c = dataSource.getConnection()) { return pending(c,source); }
        catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }
    private Candidate pending(Connection c, WorkSourceRegistry.Source source) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT batch_id,position,issue_id,issue_key,tracker_url FROM work_scan_candidate WHERE source_id=? ORDER BY position LIMIT 1")) {
            ps.setObject(1,source.id()); try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Candidate(rs.getObject(1,UUID.class),rs.getInt(2),new WorkIssueLocation(
                        new WorkIssueRef(source.type(),source.origin(),source.projectId(),rs.getString(3)),rs.getString(4),java.net.URI.create(rs.getString(5))));
            }
        }
    }
    private void advance(Connection c, UUID source, String next) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE work_source SET scan_cursor=?,scan_requested=?,scan_batch=NULL,scan_next_cursor=NULL,health='healthy',checked_at=now() WHERE id=?")) {
            ps.setString(1,next); ps.setBoolean(2,next != null); ps.setObject(3,source); ps.executeUpdate();
        }
    }
}
