package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.work.WorkDraft;
import dev.codespire.contract.work.WorkDraftLifecycle;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkItemLifecycle;
import dev.codespire.contract.work.WorkPreparation;
import dev.codespire.orchestrator.factory.BuildDefaults;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

/**
 * Registers an approved specification and plan as the preparation the build runs (M4 slice 3, A4), under the item lock
 * and in the transaction that approved the plan, by its result gate or by plan {@code auto}. It is the system path
 * beside {@link WorkItemTransitions#prepare}, which refuses once any attempt of the generation exists: here only a
 * build-or-later attempt refuses, so the specification and plan attempts that wrote this draft do not.
 *
 * <p>The draft names the build setup revision its plan was dispatched with (decision 15). A setup saved since then is a
 * refusal, {@code draft_setup_changed}: the plan was made for other coordinates. The dispatcher bought the plan run only
 * under the setup its specification was dispatched with, so that revision names the specification's base commit too. The ticket is not read again, so an edit
 * to it changes nothing about what was approved (Review Focus 7).
 *
 * <p>Until a build runs a plan step by step, a plan of more than one step waits at {@code plan_steps_unavailable}, so
 * nothing builds the first step alone and delivers it as the whole plan.
 */
@ApplicationScoped
public class WorkDraftRegistration {
    @Inject WorkItemTransitions transitions;
    @Inject WorkItemStore store;
    @Inject WorkItemArtifacts artifacts;
    @Inject BuildDefaults defaults;
    @Inject WorkClock clock;

    /**
     * What follows a draft approval: the next phase entered as an ordinary entry after a specification, the registration
     * after a plan. The caller appends what this returns.
     *
     * @param approved the item as {@link WorkDraftLifecycle#approved} left it, that decision already appended
     */
    WorkItemEvent afterApproval(Connection c, List<EventEnvelope> history, WorkItemEvent approved) throws SQLException, java.io.IOException {
        WorkDraft draft = approved.draft();
        if (!"plan".equals(approved.phase()) || draft == null || !draft.planApproved())
            return transitions.enterPrepared(c, history, approved, false, clock.now(), WorkItemLifecycle.EntryMode.ORDINARY);
        BuildDefaults.Defaults setup = defaults.get(c, approved.repositoryId(), false);
        String refused = refusal(c, approved, setup);
        if (refused != null) return WorkDraftLifecycle.registrationRefused(approved, refused);
        WorkItemEvent registered = WorkDraftLifecycle.registered(approved, preparation(draft, setup));
        store.appendDecision(c, history, registered, "draft-registered:" + draft.plan().storedId());
        return transitions.enterPrepared(c, store.history(approved.workItemId()), registered, false, clock.now(),
                WorkItemLifecycle.EntryMode.ORDINARY);
    }

    /** Why this draft may not be registered now; null when it may. */
    private String refusal(Connection c, WorkItemEvent item, BuildDefaults.Defaults setup) throws SQLException {
        if (buildAttempts(c, item) > 0) return "explicit_readmission_required";
        if (setup.revision() != item.draft().setupRevision()) return "draft_setup_changed";
        if (steps(c, item) > 1) return "plan_steps_unavailable";
        return null;
    }

    /** Attempts of this generation past the draft: a build, or anything after one. Spec and plan attempts do not count (A4). */
    private static long buildAttempts(Connection c, WorkItemEvent item) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT count(*) FROM work_phase_attempt WHERE work_item_id=? AND generation=? AND phase NOT IN ('spec','plan')")) {
            ps.setString(1, item.workItemId());
            ps.setLong(2, item.generation());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getLong(1);
            }
        }
    }

    /** The approved plan's step count, read from the stored bytes the approval bound. */
    private int steps(Connection c, WorkItemEvent item) throws SQLException {
        WorkDraft draft = item.draft();
        String text = artifacts.read(c, item.workItemId(), draft.plan().storedId())
                .orElseThrow(() -> new IllegalStateException("Work item " + item.workItemId() + " names a plan it does not store"));
        PlanRules.Checked checked = PlanRules.accept(text, new PlanRules.Bound(WorkPreparation.STEPS_BINDING, draft.specification().sha256(), Long.MAX_VALUE));
        if (!checked.valid()) throw new IllegalStateException("Work item " + item.workItemId() + " approved a plan that breaks " + checked.detail());
        return checked.steps().size();
    }

    /** The draft's artifacts and base, under the build setup the plan was made for, which the caller proved current. */
    private static WorkPreparation preparation(WorkDraft draft, BuildDefaults.Defaults setup) {
        return new WorkPreparation(draft.specification(), draft.plan(), setup.baseBranch(), draft.baseCommit(), setup.harness(), setup.model(),
                "system:draft@" + draft.setupRevision(), WorkPreparation.STEPS_BINDING, setup.effort(), setup.payWith(),
                setup.verifyCommands(), setup.verifyTimeoutSeconds());
    }
}
