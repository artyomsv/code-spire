package dev.codespire.worker.adapters;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Keeps rung 2's index bounded (ADR-026 §7.4).
 *
 * <p>Pruning costs <b>recall, never correctness</b>: the index only ever produces candidates that are
 * re-fetched and confirmed before citation, so the worst outcome of dropping a row is a caller that
 * goes unmentioned. That is what makes an unattended sweep safe here, where the same sweep over a
 * table that answered questions would not be.
 *
 * <p>Daily rather than continuous: the index grows only from files reviews actually read, so it moves
 * at the pace of review traffic rather than repository size.
 */
@ApplicationScoped
public class SymbolIndexRetention {

    @Inject
    PostgresSymbolIndex index;

    @Scheduled(every = "24h", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void sweep() {
        index.prune();
    }
}
