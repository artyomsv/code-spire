package dev.codespire.e2e.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PsqlTest {

    /**
     * Both schemas, deliberately. Distinct roles exist precisely so the gateway cannot read the
     * orchestrator's tables (deploy/e2e.sh asserts that boundary holds), and this harness reads
     * across both — so if someone later points it at a narrower role, it must fail here rather than
     * in a scenario forty seconds later.
     */
    @Test
    void readsTheOrchestratorSchema() {
        assertTrue(Long.parseLong(Psql.one("SELECT count(*) FROM orchestrator.review_status")) >= 0);
    }

    @Test
    void readsTheWorkerSchema() {
        assertTrue(Long.parseLong(Psql.one("SELECT count(*) FROM worker.comment_idempotency")) >= 0);
    }

    @Test
    void splitsColumnsAndRows() {
        assertEquals(List.of(List.of("1", "two")), Psql.rows("SELECT 1, 'two'"));
    }

    /** Two rows, so a parser that collapsed everything onto one line would be caught. */
    @Test
    void returnsOneEntryPerRow() {
        assertEquals(List.of(List.of("1"), List.of("2")),
                Psql.rows("SELECT generate_series(1, 2)"));
    }
}
