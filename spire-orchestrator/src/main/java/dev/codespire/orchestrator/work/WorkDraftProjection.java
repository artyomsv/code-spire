package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.WorkDraftBinding;
import dev.codespire.contract.work.WorkGate;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Locale;
import java.util.Set;

/**
 * The SQL side of a generated item's draft (M4 slice 3, A4), projected in the transaction that appends each event:
 * the item's preparation mode, which the copy and draft sweeps select on (decision 2), and one
 * {@code work_prepare_effect} row for each draft attempt started, by the sweep or by an automatic retry.
 *
 * <p>A revision's note travels in the aggregate stream as the note of the REJECTED result gate (decision 8). That gate
 * stays on the item through the sweep's start, so the start's row copies the note, encrypted under the attempt, for the
 * dispatcher to put in the prompt. Only a revision leaves a draft phase's result gate REJECTED with the item still
 * going: a plain reject stops it, and nothing but a readmission, which clears the gate, starts it again. An approval's
 * note is the approver's record, never an instruction to the next run.
 */
@ApplicationScoped
public class WorkDraftProjection {
    private static final Set<String> DRAFT_PHASES = Set.of("spec", "plan");
    /** The decisions that start a draft attempt: the sweep's start, and the automatic retry (slice 6). */
    private static final Set<String> STARTS = Set.of("PHASE_STARTED", "DRAFT_RETRY_REQUESTED");

    @Inject EncryptionService encryption;

    void project(Connection c, WorkItemEvent item) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE work_item SET preparation_mode=? WHERE id=?")) {
            ps.setString(1, item.draft() == null ? "composed" : "generated");
            ps.setString(2, item.workItemId());
            ps.executeUpdate();
        }
        if (item.draft() == null || !DRAFT_PHASES.contains(item.phase()) || !STARTS.contains(item.milestone())) return;
        String note = revisionNote(item);
        // A replayed projection of the same start inserts nothing: the attempt is the row's identity.
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO work_prepare_effect(attempt_id,work_item_id,generation,kind,state,note) VALUES (?,?,?,?,'pending',?)
                ON CONFLICT DO NOTHING
                """)) {
            ps.setObject(1, item.progress().attemptId());
            ps.setString(2, item.workItemId());
            ps.setLong(3, item.generation());
            ps.setString(4, WorkDraftBinding.Kind.valueOf(item.phase().toUpperCase(Locale.ROOT)).name());
            ps.setBytes(5, note == null ? null : encryption.encrypt(note.getBytes(StandardCharsets.UTF_8),
                    WorkPrepareReads.noteAad(item.progress().attemptId())));
            ps.executeUpdate();
        }
    }

    /** The note of the revision that sent this phase back to the sweep, or null when no revision did. */
    private static String revisionNote(WorkItemEvent item) {
        WorkGate gate = item.gate();
        boolean revised = gate != null && "REJECTED".equals(gate.state()) && item.phase().equals(gate.phase())
                && gate.generation() == item.generation() && revisions(item) > 0;
        return revised ? gate.note() : null;
    }

    private static int revisions(WorkItemEvent item) {
        return "spec".equals(item.phase()) ? item.draft().specificationRevisions() : item.draft().planRevisions();
    }
}
