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
 * Starts the specification and plan runs of an item admitted as generated (M4 slice 3, A5, decision 10), the sibling of
 * {@link WorkPreparationSweep}: it selects items waiting at {@code spec / awaiting_input / specification_to_write} or
 * {@code plan / awaiting_input / plan_to_write} whose recorded mode is {@code generated}, whichever way they got there
 * (admission, an approved specification, a revision), asks the same local questions of the build setup before anything is
 * bought, observes the source and the ticket as the copy sweep does, and records a refusal with the copy sweep's
 * backoff. The start itself is
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
    @Inject WorkItemTransitions transitions;
    @Inject WorkPreparationSweep attempts;
    @Inject BuildDefaults defaults;

    @Scheduled(every = "${spire.work-draft-interval:20s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void sweep() {
        for (String id : candidates()) {
            try { start(id); }
            catch (RuntimeException failure) { LOG.warnf(failure, "work item %s could not start its specification or plan", id); }
        }
    }

    /** The sweep's trigger asked of the item itself, so the projection and the captured snapshot cannot disagree. */
    static boolean waiting(WorkItemEvent item) {
        if (item.draft() == null || item.preparation() != null || !"awaiting_input".equals(item.workflowStatus())) return false;
        return "spec".equals(item.phase()) && "specification_to_write".equals(item.reason())
                || "plan".equals(item.phase()) && "plan_to_write".equals(item.reason());
    }

    private List<String> candidates() {
        List<String> ids = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                SELECT i.id FROM work_item i
                  LEFT JOIN work_item_preparation_attempt a
                         ON a.work_item_id = i.id AND a.generation = i.generation
                 WHERE i.workflow_status = 'awaiting_input' AND i.preparation_mode = 'generated'
                   AND (i.phase = 'spec' AND i.reason = 'specification_to_write' OR i.phase = 'plan' AND i.reason = 'plan_to_write')
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
        WorkItemTransitions.Outcome outcome = refused == null ? startObserved(item, expectedRevision) : null;
        if (outcome != null && outcome.status() == 200) {
            attempts.clear(id, item.generation());
            return outcome.reason();
        }
        if (outcome != null) refused = outcome.reason();
        // Somebody else moved the item: a fact about the item, not about the factory's ability to start it.
        if (!"work_item_changed".equals(refused)) attempts.record(id, item.generation(), expectedRevision, refused);
        return refused;
    }

    /**
     * Observes the source and the ticket, a remote read made outside any lock as the copy sweep makes it, then starts
     * under the lock against what was observed. A disabled source or an unreadable ticket is refused before anything
     * is bought.
     */
    private WorkItemTransitions.Outcome startObserved(WorkItemEvent item, long expectedRevision) {
        WorkItemTransitions.Observation observed = transitions.observe(item.sourceId(), item.issue());
        if (observed.evidence().failure() != null) return new WorkItemTransitions.Outcome(503, observed.evidence().failure(), item);
        return drafts.start(item.workItemId(), expectedRevision, observed);
    }
}
