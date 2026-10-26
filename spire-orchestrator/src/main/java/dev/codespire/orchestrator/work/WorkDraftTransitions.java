package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunWorkArtifact;
import dev.codespire.contract.work.ResolveGate;
import dev.codespire.contract.work.WorkDraft;
import dev.codespire.contract.work.WorkDraftBinding;
import dev.codespire.contract.work.WorkDraftLifecycle;
import dev.codespire.contract.work.WorkGate;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkItemLifecycle;
import dev.codespire.contract.work.WorkPreparation;
import dev.codespire.contract.work.WorkProgress;
import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * The draft transitions (M4 slice 3, decision 10): every specification or plan run starts here, and its result is
 * applied here, through the decider under the item lock, in the lock order {@link WorkItemTransitions} keeps. A revision
 * from the dashboard is decided here too.
 */
@ApplicationScoped
public class WorkDraftTransitions {
    @Inject WorkItemTransitions transitions;
    @Inject WorkClock clock;
    @Inject WorkItemStore store;
    @Inject WorkItemArtifacts artifacts;
    @Inject WorkDraftComments comments;
    @Inject EncryptionService encryption;
    @Inject WorkDraftRegistration registration;

    /**
     * Where a draft run started: the commit it cloned, the setup revision it was dispatched with (decision 15), and
     * whether it is already the one automatic retry.
     */
    record Origin(String baseCommit, long setupRevision, boolean retried) {}

    /** A draft run's result, as its effect row bound it, with what the run spent. */
    record DraftResult(UUID attempt, WorkDraftBinding.Kind kind, RunResult result, WorkDraftLifecycle.RunUsage usage, Origin origin) {}

    /** One locked view of an item: the connection, its history and the item as it stands. */
    private record Locked(Connection c, List<EventEnvelope> history, WorkItemEvent item) {}

    /**
     * Starts the item's draft attempt with {@link WorkItemLifecycle.EntryMode#DRAFT}, only while it is still at the
     * revision the sweep captured and still waiting for its draft: an item that moved meanwhile is refused as a second
     * sweep would be. The PHASE_STARTED inserts the attempt's effect row in the same transaction.
     *
     * <p>The authority is the one the sweep observed before buying anything, compared here under the lock as every
     * other phase entry compares it, and the policy is selected again from the ticket as it read then: a source switched
     * off, a removed label or an edited profile stops the run instead of starting it from the stored selection.
     */
    WorkItemTransitions.Outcome start(String id, long expectedRevision, WorkItemTransitions.Observation observed) {
        return transitions.underLock(id, (c, history, item) -> {
            if (!transitions.current(c, observed))
                return new WorkItemTransitions.Outcome(503, "authority_changed_during_read", item);
            if (history.size() != expectedRevision || !WorkDraftSweep.waiting(item))
                return new WorkItemTransitions.Outcome(409, "work_item_changed", item);
            WorkItemEvent checked = item.decision(observed.policy().revision(), WorkItemTransitions.authority(observed.source()),
                    transitions.select(observed, item), item.phase(), item.workflowStatus(), item.reason(), "POLICY_CHECKED",
                    item.gate(), item.progress());
            WorkItemEvent next = transitions.enterPrepared(c, history, checked, false, clock.now(), WorkItemLifecycle.EntryMode.DRAFT);
            next = transitions.decide(c, new WorkItemTransitions.Decision(history, next, "draft-start:" + UUID.randomUUID()));
            return new WorkItemTransitions.Outcome(200, next.reason(), next);
        });
    }

    /**
     * Applies a stored draft result, only to the attempt the item is still running: a result for an attempt a revision,
     * a readmission or a takeover replaced is refused {@code work_item_changed}: its artifact, the gate and the phase stay
     * as they are (Review Focus 28), and only what the run bought is counted, by {@link #late}.
     */
    WorkItemTransitions.Outcome apply(String id, DraftResult result) {
        return transitions.underLock(id, (c, history, item) -> {
            String phase = result.kind() == WorkDraftBinding.Kind.SPEC ? "spec" : "plan";
            Locked locked = new Locked(c, history, item);
            if (item.draft() == null || !result.attempt().equals(item.progress().attemptId()) || !"active".equals(item.workflowStatus())
                    || !phase.equals(item.phase()))
                return late(locked, result);
            if (result.result() instanceof RunWorkArtifact artifact) return artifact(locked, result, artifact.text());
            return decided(locked, WorkDraftLifecycle.runFailed(item, result.usage()), "draft-failed:" + result.attempt());
        });
    }

    /**
     * A result for an attempt the item no longer runs still bought its usage (review of PR 287, H-2): after a takeover,
     * a retirement or a stop, the run's wall time, calls and cost are added to the item's counters, and unknown usage
     * makes the item's usage unknown, as a late build is accounted. Once per attempt, under its key; an attempt already
     * completed counted its usage when it completed, and the attempt still running is not late.
     */
    private WorkItemTransitions.Outcome late(Locked locked, DraftResult result) throws SQLException, java.io.IOException {
        WorkItemEvent item = locked.item();
        WorkItemTransitions.Outcome changed = new WorkItemTransitions.Outcome(409, "work_item_changed", item);
        String key = "late-draft-result:" + result.attempt();
        if (locked.history().stream().anyMatch(event -> key.equals(event.correlationId()))) return changed;
        if (result.attempt().equals(item.progress().attemptId()) && "active".equals(item.workflowStatus())) return changed;
        if (!uncounted(locked.c(), item.workItemId(), result.attempt())) return changed;
        WorkDraftLifecycle.RunUsage usage = result.usage();
        WorkProgress progress = item.progress().account(usage.wallSeconds(), usage.costMillicents(), usage.calls());
        WorkItemEvent next = WorkItemLifecycle.state(item, item.workflowStatus(), item.reason(), "LATE_DRAFT_RESULT", item.gate(),
                usage.known() ? progress : progress.unknownUsage());
        store.appendDecision(locked.c(), locked.history(), next, key);
        return new WorkItemTransitions.Outcome(409, "work_item_changed", next);
    }

    /** Whether the attempt is one of this item's draft attempts, dispatched and never completed. */
    private static boolean uncounted(Connection c, String id, UUID attempt) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT 1 FROM work_phase_attempt a WHERE a.id=? AND a.work_item_id=? AND a.phase IN ('spec','plan')
                   AND a.state<>'completed' AND EXISTS (SELECT 1 FROM work_prepare_effect e WHERE e.attempt_id=a.id)
                """)) {
            ps.setObject(1, attempt);
            ps.setString(2, id);
            try (ResultSet rs = ps.executeQuery()) { return rs.next(); }
        }
    }

    /** A validated artifact: its text as stored, the plan's steps (none for a specification), or why it was refused. */
    private record Validated(String text, List<WorkArtifacts.Step> steps, String refusal) {}

    /** A valid artifact is stored and written; an invalid one is retried once, then the item waits (§6.3, §6.4). */
    private WorkItemTransitions.Outcome artifact(Locked locked, DraftResult result, String raw) throws SQLException, java.io.IOException {
        String text = UntrustedText.stripControls(raw);
        Validated validated = result.kind() == WorkDraftBinding.Kind.SPEC
                ? new Validated(text, List.of(), SpecificationRules.check(text).orElse(null)) : plan(locked, text);
        if (validated.refusal() != null && result.origin().retried())
            return decided(locked, WorkDraftLifecycle.invalid(locked.item(), result.usage()), "draft-invalid:" + result.attempt());
        if (validated.refusal() != null) return retry(locked, result, validated.refusal());
        return written(locked, result, validated);
    }

    /**
     * A plan is checked against the approved specification as stored and the most steps the item can build now, its
     * step limit, the cursor's 50 and its runs left (§6.4, {@link dev.codespire.contract.work.WorkProgress#planStepBound}), then its
     * worst-case step prompt against the budget (A8), so that no step is refused at dispatch after the plan is approved.
     */
    private Validated plan(Locked locked, String text) throws SQLException {
        WorkItemEvent item = locked.item();
        WorkPreparation.Artifact approved = item.draft().specification();
        String specification = artifacts.read(locked.c(), item.workItemId(), approved.storedId())
                .orElseThrow(() -> new IllegalStateException("Work item " + item.workItemId() + " names a specification it does not store"));
        PlanRules.Checked checked = PlanRules.accept(text, new PlanRules.Bound(WorkPreparation.STEPS_BINDING, approved.sha256(),
                item.progress().planStepBound(item.policy().limits())));
        if (!checked.valid()) return new Validated(text, List.of(), PlanRules.sentence(checked.detail()));
        return new Validated(text, checked.steps(), StepPromptBudget.refusal(specification, checked.steps()).orElse(null));
    }

    private WorkItemTransitions.Outcome written(Locked locked, DraftResult result, Validated validated) throws SQLException, java.io.IOException {
        WorkItemEvent item = locked.item();
        boolean specification = result.kind() == WorkDraftBinding.Kind.SPEC;
        String text = validated.text(), sha256 = WorkPreparation.digest(text);
        UUID stored = artifacts.store(locked.c(), item.workItemId(), specification ? WorkItemArtifacts.Kind.SPEC : WorkItemArtifacts.Kind.PLAN, text);
        WorkDraft.Written written = new WorkDraft.Written(new WorkPreparation.Artifact(item.issue(), sha256,
                WorkPreparation.Origin.STORED, stored), result.origin().baseCommit(), result.origin().setupRevision());
        WorkItemEvent next = WorkDraftLifecycle.written(item, written, result.usage());
        store.appendDecision(locked.c(), locked.history(), next, "draft-written:" + result.attempt());
        comments.enqueue(locked.c(), next, markup -> specification ? WorkDraftComments.specification(text, sha256, markup)
                : WorkDraftComments.plan(validated.steps(), sha256, markup));
        List<EventEnvelope> history = store.history(item.workItemId());
        WorkItemEvent after = WorkDraftLifecycle.afterWritten(next, new WorkItemLifecycle.GateOpening(history.size(), clock.now(), UUID.randomUUID()));
        if ("DRAFT_APPROVED".equals(after.milestone())) {
            store.appendDecision(locked.c(), history, after, "draft-approved:" + result.attempt());
            after = registration.afterApproval(locked.c(), store.history(item.workItemId()), after);
            history = store.history(item.workItemId());
        }
        return decided(new Locked(locked.c(), history, next), after, "draft-next:" + result.attempt());
    }

    /** A dashboard revision of an open specification or plan result gate (decision 8). */
    record Revise(UUID gateId, long expectedVersion, String key, String note, String resolver) {}

    /**
     * Revise with a note, from the dashboard only (A6): {@link ResolveGate} has no revise, so a tracker command or a pull
     * request review keeps plain approve and reject. The gate resolves REJECTED with the note and the item waits for the
     * draft sweep, which starts the next run with the note in its prompt; at most {@link WorkDraft#MAX_REVISIONS} per
     * phase, then only Approve or Reject remain.
     */
    WorkItemTransitions.Outcome revise(Revise revise) {
        String id = transitions.gateItem(revise.gateId());
        return transitions.underLock(id, (c, history, item) -> {
            WorkGate gate = transitions.gateFromHistory(id, revise.gateId());
            if (revise.key().equals(gate.answerKey()) && revise.resolver().equals(gate.resolver()) && "dashboard".equals(gate.channel())
                    && "REJECTED".equals(gate.state()) && Objects.equals(revise.note(), gate.note()))
                return new WorkItemTransitions.Outcome(200, "answer_already_applied", item);
            if (Set.of("suspended", "retired").contains(item.workflowStatus())) return new WorkItemTransitions.Outcome(409, "item_not_active", item);
            if (gate.version() != revise.expectedVersion() || !"OPEN".equals(gate.state()) || item.gate() == null
                    || !gate.id().equals(item.gate().id()) || !clock.now().isBefore(gate.expiresAt()))
                return new WorkItemTransitions.Outcome(409, "gate_changed", item);
            if (!WorkDraftLifecycle.resultGate(item, gate)) return new WorkItemTransitions.Outcome(409, "revise_unavailable", item);
            int used = "spec".equals(item.phase()) ? item.draft().specificationRevisions() : item.draft().planRevisions();
            if (used >= WorkDraft.MAX_REVISIONS) return new WorkItemTransitions.Outcome(409, "revision_limit_reached", item);
            WorkItemEvent revised = WorkDraftLifecycle.revised(item, new WorkDraftLifecycle.Revision(revise.resolver(), revise.key(), revise.note()));
            return decided(new Locked(c, history, item), revised, "draft-revised:" + revise.gateId() + ":" + revise.key());
        });
    }

    /**
     * Readmit with Regenerate (A7): a new generation that writes its specification and plan again, under the policy
     * selected now, as readmission selects it. Every readmission refusal comes first with its own reason, so Regenerate is
     * refused wherever readmit is; a composed item, which has no draft, is refused {@code regenerate_unavailable}. The
     * new generation enters intake at once and parks where the draft sweep looks (decision 10).
     */
    WorkItemTransitions.Outcome regenerate(String id, long expectedRevision) {
        WorkItemEvent reference = transitions.require(id);
        WorkItemTransitions.Observation observed = transitions.observe(reference.sourceId(), reference.issue());
        return transitions.underLock(id, (c, history, item) -> {
            if (!transitions.current(c, observed)) return new WorkItemTransitions.Outcome(503, "authority_changed_during_read", item);
            if (history.size() != expectedRevision) return new WorkItemTransitions.Outcome(409, "work_item_changed", item);
            WorkItemTransitions.Outcome refused = WorkItemTransitions.readmitRefusal(c, history, item);
            if (refused != null) return refused;
            if (item.draft() == null) return new WorkItemTransitions.Outcome(409, "regenerate_unavailable", item);
            if (observed.evidence().failure() != null) return new WorkItemTransitions.Outcome(503, observed.evidence().failure(), item);
            WorkItemEvent checked = item.decision(observed.policy().revision(), WorkItemTransitions.authority(observed.source()),
                    transitions.select(observed, null), item.phase(), item.workflowStatus(), item.reason(), "POLICY_CHECKED", item.gate(), item.progress());
            WorkItemEvent next = WorkDraftLifecycle.regenerated(checked);
            store.appendDecision(c, history, next, "regenerate:" + UUID.randomUUID());
            List<EventEnvelope> regenerated = store.history(id);
            next = transitions.enterPrepared(c, regenerated, next, false, clock.now(), WorkItemTransitions.entryMode(regenerated));
            return decided(new Locked(c, store.history(id), next), next, "transition:" + UUID.randomUUID());
        });
    }

    /** The one automatic retry: a new attempt, whose effect row carries the refused attempt and the error, encrypted. */
    private WorkItemTransitions.Outcome retry(Locked locked, DraftResult result, String error) throws SQLException, java.io.IOException {
        UUID next = UUID.randomUUID();
        // The refused attempt is recorded completed first: one attempt of a phase is started at a time (V87).
        store.appendDecision(locked.c(), locked.history(), WorkDraftLifecycle.artifactRefused(locked.item(), result.usage()),
                "draft-refused:" + result.attempt());
        WorkItemEvent retried = WorkDraftLifecycle.retryRequested(locked.item(), result.usage(), new WorkDraftLifecycle.Restart(next, clock.now()));
        WorkItemTransitions.Outcome outcome = decided(new Locked(locked.c(), store.history(locked.item().workItemId()), locked.item()), retried,
                "draft-retry:" + result.attempt());
        if (!"DRAFT_RETRY_REQUESTED".equals(retried.milestone())) return outcome;
        try (PreparedStatement ps = locked.c().prepareStatement("UPDATE work_prepare_effect SET retry_of=?,validation_error=?,updated_at=now() WHERE attempt_id=?")) {
            ps.setObject(1, result.attempt());
            ps.setBytes(2, encryption.encrypt(error.getBytes(StandardCharsets.UTF_8), WorkPrepareReads.errorAad(next)));
            ps.setObject(3, next);
            if (ps.executeUpdate() != 1) throw new IllegalStateException("The retry's effect row was not inserted");
        }
        return outcome;
    }

    private WorkItemTransitions.Outcome decided(Locked locked, WorkItemEvent next, String cause) throws SQLException, java.io.IOException {
        WorkItemEvent decided = transitions.decide(locked.c(), new WorkItemTransitions.Decision(locked.history(), next, cause));
        return new WorkItemTransitions.Outcome(200, decided.reason(), decided);
    }
}
