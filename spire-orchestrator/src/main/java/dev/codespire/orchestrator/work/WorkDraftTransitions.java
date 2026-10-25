package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.work.WorkDraft;
import dev.codespire.contract.work.WorkDraftBinding;
import dev.codespire.contract.work.WorkDraftLifecycle;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkItemLifecycle;
import dev.codespire.contract.work.WorkPreparation;
import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The draft transitions (M4 slice 3, decision 10): every specification or plan run starts here, and its result is
 * applied here, through the decider under the item lock, in the lock order {@link WorkItemTransitions} keeps.
 */
@ApplicationScoped
public class WorkDraftTransitions {
    @Inject WorkItemTransitions transitions;
    @Inject WorkClock clock;
    @Inject WorkItemStore store;
    @Inject WorkItemArtifacts artifacts;
    @Inject WorkDraftComments comments;
    @Inject EncryptionService encryption;

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
     * a readmission or a takeover replaced is refused {@code work_item_changed} and changes nothing (Review Focus 28).
     */
    WorkItemTransitions.Outcome apply(String id, DraftResult result) {
        return transitions.underLock(id, (c, history, item) -> {
            String phase = result.kind() == WorkDraftBinding.Kind.SPEC ? "spec" : "plan";
            if (item.draft() == null || !result.attempt().equals(item.progress().attemptId()) || !"active".equals(item.workflowStatus())
                    || !phase.equals(item.phase()))
                return new WorkItemTransitions.Outcome(409, "work_item_changed", item);
            Locked locked = new Locked(c, history, item);
            if (result.result() instanceof RunResult.RunWorkArtifact artifact) return artifact(locked, result, artifact.text());
            return decided(locked, WorkDraftLifecycle.runFailed(item, result.usage()), "draft-failed:" + result.attempt());
        });
    }

    /** A valid artifact is stored and written; an invalid one is retried once, then the item waits (§6.3). */
    private WorkItemTransitions.Outcome artifact(Locked locked, DraftResult result, String raw) throws SQLException, java.io.IOException {
        if (result.kind() != WorkDraftBinding.Kind.SPEC) throw new IllegalStateException("A plan artifact is validated from slice 7 on");
        String text = UntrustedText.stripControls(raw);
        Optional<String> refused = SpecificationRules.check(text);
        if (refused.isPresent() && result.origin().retried())
            return decided(locked, WorkDraftLifecycle.invalid(locked.item(), result.usage()), "draft-invalid:" + result.attempt());
        if (refused.isPresent()) return retry(locked, result, refused.orElseThrow());
        return written(locked, result, text);
    }

    private WorkItemTransitions.Outcome written(Locked locked, DraftResult result, String text) throws SQLException, java.io.IOException {
        WorkItemEvent item = locked.item();
        UUID stored = artifacts.store(locked.c(), item.workItemId(), WorkItemArtifacts.Kind.SPEC, text);
        WorkDraft.Written written = new WorkDraft.Written(new WorkPreparation.Artifact(item.issue(), WorkPreparation.digest(text),
                WorkPreparation.Origin.STORED, stored), result.origin().baseCommit(), result.origin().setupRevision());
        WorkItemEvent next = WorkDraftLifecycle.written(item, written, result.usage());
        store.appendDecision(locked.c(), locked.history(), next, "draft-written:" + result.attempt());
        comments.enqueue(locked.c(), next, text);
        List<EventEnvelope> history = store.history(item.workItemId());
        WorkItemEvent after = WorkDraftLifecycle.afterWritten(next, new WorkItemLifecycle.GateOpening(history.size(), clock.now(), UUID.randomUUID()));
        if ("DRAFT_APPROVED".equals(after.milestone())) {
            store.appendDecision(locked.c(), history, after, "draft-approved:" + result.attempt());
            history = store.history(item.workItemId());
            after = transitions.enterPrepared(locked.c(), history, after, false, clock.now(), WorkItemLifecycle.EntryMode.ORDINARY);
        }
        return decided(new Locked(locked.c(), history, next), after, "draft-next:" + result.attempt());
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
