package dev.codespire.contract.work;

import java.util.UUID;

/**
 * Immutable identity of one specification or plan run (M4 slice 3, A4). It binds a provisional digest of the setup
 * revision, the ticket and the kind, never a preparation binding: no preparation exists until both drafts are
 * approved. Only the digest's shape is checked here; the orchestrator computes it.
 */
public record WorkDraftBinding(String workItemId, long generation, UUID attemptId, Kind kind, String provisionalDigest) {

    /** What the draft run writes. */
    public enum Kind { SPEC, PLAN }

    public WorkDraftBinding {
    }
}
