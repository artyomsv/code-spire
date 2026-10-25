package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.orchestrator.factory.BuildDefaults;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

/**
 * Starts the specification run of an item admitted as generated (M4 slice 3, A5, decision 10), the sibling of
 * {@link WorkPreparationSweep}: it selects items waiting at {@code spec / awaiting_input / specification_to_write}
 * whose recorded mode is {@code generated}, asks the same local questions of the build setup before anything is
 * bought, and records a refusal with the copy sweep's backoff. The start itself is
 * {@link WorkDraftTransitions#start}, under the item lock and at the revision captured here.
 */
@ApplicationScoped
public class WorkDraftSweep {
    private static final Logger LOG = Logger.getLogger(WorkDraftSweep.class);
    /** Read-then-write, as the copy sweep: the locked start re-checks what was read. */
    private static final int MAX_ITEMS_PER_SWEEP = 5;

    @Inject DataSource dataSource;
    @Inject WorkItemStore store;
    @Inject WorkDraftTransitions drafts;
    @Inject WorkPreparationSweep attempts;
    @Inject BuildDefaults defaults;

    @Scheduled(every = "${spire.work-draft-interval:20s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void sweep() {
        for (String id : candidates()) {
            try { start(id); }
            catch (RuntimeException failure) { LOG.warnf(failure, "work item %s could not start its specification", id); }
        }
    }

    /** The sweep's trigger asked of the item itself, so the projection and the captured snapshot cannot disagree. */
    static boolean waiting(WorkItemEvent item) {
        return item.draft() != null && item.preparation() == null && "spec".equals(item.phase())
                && "awaiting_input".equals(item.workflowStatus()) && "specification_to_write".equals(item.reason());
    }

    private List<String> candidates() {
        List<String> ids = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                SELECT i.id FROM work_item i
                  LEFT JOIN work_item_preparation_attempt a
                         ON a.work_item_id = i.id AND a.generation = i.generation
                 WHERE i.phase = 'spec' AND i.workflow_status = 'awaiting_input'
                   AND i.reason = 'specification_to_write' AND i.preparation_mode = 'generated'
                   AND (a.retry_after IS NULL OR a.retry_after <= now())
                 ORDER BY (a.work_item_id IS NOT NULL), COALESCE(a.retry_after, i.updated_at), i.updated_at
                 LIMIT ?
                """)) {
            ps.setInt(1, MAX_ITEMS_PER_SWEEP);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getString(1)); }
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        return ids;
    }

    /** @return the reason the item now stands at, or the refusal recorded as its health */
    String start(String id) {
        List<EventEnvelope> history = store.history(id);
        if (history.isEmpty()) return "work_item_unknown";
        long expectedRevision = history.size();
        WorkItemEvent item = (WorkItemEvent) history.getLast().payload();
        if (!waiting(item)) return "work_item_changed";
        String refused = attempts.localRefusal(defaults.get(item.repositoryId()));
        if (refused == null) {
            WorkItemTransitions.Outcome outcome = drafts.start(id, expectedRevision);
            if (outcome.status() == 200) {
                attempts.clear(id, item.generation());
                return outcome.reason();
            }
            refused = outcome.reason();
        }
        // Somebody else moved the item: a fact about the item, not about the factory's ability to start it.
        if (!"work_item_changed".equals(refused)) attempts.record(id, item.generation(), expectedRevision, refused);
        return refused;
    }
}
