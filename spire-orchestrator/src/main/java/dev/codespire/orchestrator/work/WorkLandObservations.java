package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.WorkItemEvent;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import javax.sql.DataSource;

/**
 * A merge made by hand that the pull request read has not confirmed (M4 land, spec §7.8): GitHub could not be read,
 * or did not show the merge yet. The webhook leaves one row in the same transaction as its receipt, and each land
 * sweep asks {@link WorkLandTransitions#landedByHuman} again until the read settles it. An uncertain observation is
 * recovered by reading, so a transient read failure never leaves through the dead-letter topic, which nothing drains.
 */
@ApplicationScoped
public class WorkLandObservations {
    static final String MERGE_NOT_CONFIRMED = "merge_not_confirmed";
    /** An item in one of these has nothing left for a merge observation to record. */
    static final Set<String> FINAL = Set.of("retired", "completed");

    @Inject DataSource dataSource;
    @Inject WorkItemStore store;
    @Inject WorkLandTransitions transitions;
    @Inject WorkClock clock;

    record Observation(String item, long generation, long pullRequest, String delivery, String mergedBy) {}

    /** True when the read did not decide the merge: it failed, or GitHub does not show the merge yet. */
    static boolean unsettled(WorkItemTransitions.Outcome outcome) {
        return outcome.status() >= 500 || MERGE_NOT_CONFIRMED.equals(outcome.reason());
    }

    /** Keeps the merge for the sweep and receipts the delivery, both or neither; the first observation of a merge wins. */
    void observe(WorkItemEvent item, long pullRequest, String mergedBy, String delivery, String reason) {
        QuarkusTransaction.requiringNew().run(() -> {
            try (Connection c = dataSource.getConnection()) {
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO work_land_observation(work_item_id,generation,pr_number,delivery_id,merged_by,reason)
                        VALUES (?,?,?,?,?,?) ON CONFLICT DO NOTHING
                        """)) {
                    ps.setString(1, item.workItemId()); ps.setLong(2, item.generation()); ps.setLong(3, pullRequest);
                    ps.setString(4, delivery); ps.setString(5, mergedBy); ps.setString(6, reason);
                    ps.executeUpdate();
                }
                WorkItemControl.receipt(c, item.workItemId(), "scm", delivery, "merge_observed:" + reason);
            } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        });
    }

    /** Reads every due observation again; one that still does not settle waits {@link WorkLandSweep#RECHECK}. */
    void drain() {
        List<Observation> due = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT work_item_id,generation,pr_number,delivery_id,merged_by FROM work_land_observation"
                        + " WHERE next_check_at IS NULL OR next_check_at <= ? ORDER BY created_at LIMIT 20")) {
            ps.setTimestamp(1, Timestamp.from(clock.now()));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) due.add(new Observation(rs.getString(1), rs.getLong(2), rs.getLong(3), rs.getString(4), rs.getString(5)));
            }
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        for (Observation observation : due) settle(observation);
    }

    private void settle(Observation observation) {
        WorkItemEvent item = store.load(observation.item());
        if (item == null || FINAL.contains(item.workflowStatus()) || item.generation() != observation.generation()) { end(observation); return; }
        WorkItemTransitions.Outcome outcome = transitions.landedByHuman(observation.item(), observation.delivery(), observation.pullRequest(),
                observation.mergedBy());
        if (unsettled(outcome)) later(observation, outcome.reason());
        else end(observation);
    }

    private void later(Observation observation, String reason) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE work_land_observation SET reason=?,next_check_at=?,updated_at=now() WHERE work_item_id=? AND generation=? AND pr_number=?")) {
            ps.setString(1, reason); ps.setTimestamp(2, Timestamp.from(clock.now().plus(WorkLandSweep.RECHECK)));
            ps.setString(3, observation.item()); ps.setLong(4, observation.generation()); ps.setLong(5, observation.pullRequest());
            ps.executeUpdate();
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }

    private void end(Observation observation) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM work_land_observation WHERE work_item_id=? AND generation=? AND pr_number=?")) {
            ps.setString(1, observation.item()); ps.setLong(2, observation.generation()); ps.setLong(3, observation.pullRequest());
            ps.executeUpdate();
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }
}
