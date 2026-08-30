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

    /**
     * An empty value is a value. Skipping every blank line could not tell a row whose only column is
     * {@code ''} or NULL from psql's trailing newline, so a NULL column surfaced as "the row is
     * missing" when the row existed.
     */
    @Test
    void keepsAnEmptyValueDistinctFromAMissingRow() {
        assertEquals(List.of(List.of("", "x")), Psql.rows("SELECT '', 'x'"));
    }

    /**
     * Splitting rows on newline was wrong in the PASSING direction: a value containing one became two
     * rows, inflating every count without failing anything.
     */
    @Test
    void doesNotSplitAValueThatContainsANewline() {
        assertEquals(List.of(List.of("a\nb")), Psql.rows("SELECT 'a' || chr(10) || 'b'"));
    }
}
