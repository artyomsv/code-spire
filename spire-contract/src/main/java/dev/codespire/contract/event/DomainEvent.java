package dev.codespire.contract.event;

import dev.codespire.contract.scm.ThreadRef;

/**
 * Domain events are appended ONLY by the ReviewLifecycle aggregate — the
 * single-writer source of truth (CONTRACT §4, ADR-010).
 */
public sealed interface DomainEvent {

    record ReviewRequested(String commit, String trigger) implements DomainEvent {
    }

    /** A newer commit arrived mid-run; the old run is abandoned (workers pre-check staleness, ADR-013). */
    record ReviewSuperseded(String commit) implements DomainEvent {
    }

    /** The aggregate keeps only a digest — findings live in read models (ADR-011). */
    record ReviewOutcomeRecorded(String commit, int findingsCount, String summaryDigest) implements DomainEvent {
    }

    record ReviewCompleted(String commit, String summaryCommentId) implements DomainEvent {
    }

    record ReviewFailedTerminally(String commit, String phase) implements DomainEvent {
    }

    /** PR closed/merged/declined mid-run, or an operator cancelled (ADR-013). */
    record ReviewCancelled(String reason) implements DomainEvent {
    }

    record ThreadOpened(ThreadRef threadRef, String parentCommentId) implements DomainEvent {
    }

    record FollowUpRecorded(ThreadRef threadRef, String commentId) implements DomainEvent {
    }
}
