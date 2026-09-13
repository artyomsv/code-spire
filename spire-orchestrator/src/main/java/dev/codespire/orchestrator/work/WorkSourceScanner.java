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

/** Bounded sweeps. A page's cursor advances in the same transaction as all its reconciliations. */
@ApplicationScoped
public class WorkSourceScanner {
    @Inject DataSource dataSource;
    @Inject WorkSourceRegistry sources;
    @Inject WorkPolicyRegistry policies;
    @Inject WorkItemStore store;
    private record Page(List<WorkEvidence> evidence, String next) {}

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
        FutureTask<Page> task = new FutureTask<>(() -> {
            WorkSource client = sources.client(source);
            WorkPage<WorkIssueLocation> page = client.candidates(source.cursor());
            if (page.items().size() > 100 || page.nextCursor() != null && page.nextCursor().equals(source.cursor()))
                throw new WorkSourceException("Invalid candidate page");
            List<WorkEvidence> evidence = new ArrayList<>();
            for (WorkIssueLocation issue : page.items()) {
                WorkEvidence observation = WorkEvidence.read(client, issue, null);
                if (observation.failure() != null) throw new WorkSourceException("Candidate observation unavailable");
                evidence.add(observation);
            }
            return new Page(List.copyOf(evidence), page.nextCursor());
        });
        Thread.ofVirtual().start(task);
        Page page;
        try { page = task.get(30, TimeUnit.SECONDS); }
        catch (Exception failure) {
            task.cancel(true);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            sources.health(id, "scan_unavailable"); return false;
        }
        return QuarkusTransaction.requiringNew().call(() -> {
            try (Connection c = dataSource.getConnection()) {
                WorkSourceRegistry.Source current = sources.get(c, id, true).orElseThrow();
                if (!source.version().equals(current.version()) || !Objects.equals(source.cursor(), current.cursor())
                        || policies.get(c, source.repositoryId(), true).revision() != policyRevision) return false;
                String delivery = "scan-" + UUID.randomUUID();
                for (WorkEvidence evidence : page.evidence()) {
                    if (store.reconcile(source, policyRevision, evidence, delivery) == null) throw new IllegalStateException("Scan authority changed");
                }
                try (PreparedStatement ps = c.prepareStatement("UPDATE work_source SET scan_cursor=?,scan_requested=?,health='healthy',checked_at=now() WHERE id=?")) {
                    ps.setString(1, page.next()); ps.setBoolean(2, page.next() != null); ps.setObject(3, id); ps.executeUpdate();
                }
                return true;
            } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        });
    }
}
