package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkPolicy;
import dev.codespire.orchestrator.work.WorkLandEffects.Effect;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import javax.sql.DataSource;

/**
 * The merge claim of a land attempt (M4 land, spec §7.3): the approval is re-observed before the merge is claimed, as
 * delivery re-observes before its permit, and the claim commits {@code uncertain} before the merge call.
 */
@ApplicationScoped
public class WorkLandApproval {
    private static final String AUTHORITY_CHANGED = "authority_changed_during_read";

    @Inject DataSource dataSource;
    @Inject WorkItemStore store;
    @Inject WorkItemTransitions transitions;

    /**
     * Commits {@code uncertain} before the merge call, under the item lock, and only while the item is still at this
     * attempt and its approval still stands: a takeover that refused the row first wins, and a merge that was claimed is
     * never re-sent blind. Locks the source and policy first, in the order every transition takes them.
     *
     * @return null when claimed; {@link WorkLandSweep#LAND_POLICY_CHANGED} when the approval no longer stands; any
     *     other reason leaves the row for the next pass
     */
    String claim(Effect effect, String method, WorkItemTransitions.Observation observed) {
        return QuarkusTransaction.requiringNew().call(() -> {
            try (Connection c = dataSource.getConnection()) {
                if (!transitions.current(c, observed)) return AUTHORITY_CHANGED;
                WorkItemTransitions.lockItem(c, effect.item());
                List<EventEnvelope> history = store.history(effect.item());
                WorkItemEvent item = (WorkItemEvent) history.getLast().payload();
                if (!WorkLandSweep.atAttempt(item, effect)) return WorkLandSweep.WORK_ITEM_CHANGED;
                if (!stands(history, effect, observed)) return WorkLandSweep.LAND_POLICY_CHANGED;
                return WorkLandEffects.claimMerge(c, effect, method) ? null : WorkLandSweep.WORK_ITEM_CHANGED;
            } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        });
    }

    /** The selection, the policy revision and the source the land attempt started under, with land still {@code approve}. */
    private boolean stands(List<EventEnvelope> history, Effect effect, WorkItemTransitions.Observation observed) {
        if (!observed.source().enabled()) return false;
        WorkItemEvent item = (WorkItemEvent) history.getLast().payload();
        WorkItemEvent admitted = WorkItemTransitions.facts(history).stream()
                .filter(event -> "PHASE_STARTED".equals(event.milestone()) && effect.id().equals(event.progress().attemptId()))
                .findFirst().orElse(null);
        if (admitted == null) return false;
        WorkPolicy.Selection selection = transitions.select(observed, item);
        return selection.equals(admitted.policy()) && observed.policy().revision() == admitted.policyRevision()
                && "approve".equals(selection.effective().get(WorkPolicy.Phase.LAND));
    }
}
