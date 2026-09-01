package dev.codespire.orchestrator.llm;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ledger's spine was review-shaped. These assert it now carries a run without pretending one is
 * a review, and that the checks which made the review ledger trustworthy still hold.
 */
@QuarkusTest
class LlmChargeSubjectTest {

    @Inject
    DataSource dataSource;

    @Test
    void aRunChargeIsStorableWithoutPretendingToBeAReview() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                    INSERT INTO llm_charge (id, subject_id, subject_kind, call_ref, kind, model,
                                            pricing_mode, token_type, tokens, capability,
                                            rate_millicents_per_million, cost_millicents)
                    VALUES (gen_random_uuid(), 'run::github:acme/app:finding-1:1', 'RUN',
                            'run:run::github:acme/app:finding-1:1:1:total', 'BUILD', 'gpt-5.6',
                            'UNMETERED', 'TOTAL', 1200, 'BUILD', 0, 0)
                    """);
            try (ResultSet rs = s.executeQuery("""
                    SELECT subject_kind, capability FROM llm_charge
                     WHERE subject_id = 'run::github:acme/app:finding-1:1'
                    """)) {
                assertTrue(rs.next());
                assertEquals("RUN", rs.getString(1));
                assertEquals("BUILD", rs.getString(2));
            }
        }
    }

    @Test
    void theFactoryCallKindsAreAdmittedAndATypoIsStillRefused() throws Exception {
        // The kind CHECK is why a typo'd literal in a writer cannot silently enter the ledger. V30
        // declared it INLINE and unnamed, so Postgres generated a name from the table — a migration
        // dropping "llm_charge_kind_check", which is what it looks like it should be called, would
        // have succeeded having dropped nothing and left the old constraint refusing every factory
        // row. V40 finds it by definition instead.
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            for (String kind : new String[] {"SPEC", "PLAN", "BUILD", "FIX"}) {
                s.executeUpdate("""
                        INSERT INTO llm_charge (id, subject_id, subject_kind, call_ref, kind, model,
                                                pricing_mode, token_type, tokens, capability,
                                            rate_millicents_per_million, cost_millicents)
                        VALUES (gen_random_uuid(), 'run::github:acme/app:k:1', 'RUN',
                                'ref-%s', '%s', 'gpt-5.6', 'UNMETERED', 'TOTAL', 1, 'BUILD', 0, 0)
                        """.formatted(kind, kind));
            }
            assertThrows(Exception.class, () -> s.executeUpdate("""
                    INSERT INTO llm_charge (id, subject_id, subject_kind, call_ref, kind, model,
                                            pricing_mode, token_type, tokens,
                                            rate_millicents_per_million, cost_millicents)
                    VALUES (gen_random_uuid(), 'run::github:acme/app:x:1', 'RUN', 'ref-typo',
                            'TYPO', 'gpt-5.6', 'UNMETERED', 'TOTAL', 1, 0, 0)
                    """));
        }
    }

    @Test
    void anUnknownSubjectKindOrCapabilityIsRefused() throws Exception {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            assertThrows(Exception.class, () -> s.executeUpdate("""
                    INSERT INTO llm_charge (id, subject_id, subject_kind, call_ref, kind, model,
                                            pricing_mode, token_type, tokens,
                                            rate_millicents_per_million, cost_millicents)
                    VALUES (gen_random_uuid(), 'x', 'CAMPAIGN', 'ref-a', 'BUILD', 'm',
                            'UNMETERED', 'TOTAL', 1, 0, 0)
                    """), "subject_kind is a closed set");
            assertThrows(Exception.class, () -> s.executeUpdate("""
                    INSERT INTO llm_charge (id, subject_id, subject_kind, call_ref, kind, model,
                                            pricing_mode, token_type, tokens, capability,
                                            rate_millicents_per_million, cost_millicents)
                    VALUES (gen_random_uuid(), 'x', 'RUN', 'ref-b', 'BUILD', 'm',
                            'UNMETERED', 'TOTAL', 1, 'TELEPATHY', 0, 0)
                    """), "capability is a closed set — it cannot be backfilled, so it cannot be free text");
        }
    }

    @Test
    void aRunsChargesAreNotSummedIntoAReview() throws Exception {
        // The two id spaces are kept apart only by their prefixes, and the review reads used to
        // filter on subject_id alone. An explicit subject_kind filter is what makes that structural
        // rather than a property of how ids happen to be spelled today.
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("""
                    INSERT INTO llm_charge (id, subject_id, subject_kind, call_ref, kind, model,
                                            pricing_mode, token_type, tokens, capability,
                                            rate_millicents_per_million, cost_millicents)
                    VALUES (gen_random_uuid(), 'shared-id', 'RUN', 'ref-run', 'BUILD', 'gpt-5.6',
                            'UNMETERED', 'TOTAL', 999, 'BUILD', 0, 0)
                    """);
            s.executeUpdate("""
                    INSERT INTO llm_charge (id, subject_id, subject_kind, call_ref, kind, model,
                                            pricing_mode, token_type, tokens, capability,
                                            rate_millicents_per_million, cost_millicents)
                    VALUES (gen_random_uuid(), 'shared-id', 'REVIEW', 'ref-review', 'REVIEW',
                            'gpt-5.6', 'UNMETERED', 'TOTAL', 1, 'REVIEW', 0, 0)
                    """);
            try (ResultSet rs = s.executeQuery("""
                    SELECT SUM(tokens) FROM llm_charge
                     WHERE subject_kind = 'REVIEW' AND subject_id = 'shared-id'
                    """)) {
                assertTrue(rs.next());
                assertEquals(1, rs.getLong(1), "the run's 999 tokens must not be in a review's total");
            }
        }
    }

    @Test
    void aRunsChargeKeyDistinguishesARerunFromARedelivery() {
        // Re-run and auto-retry need OPPOSITE treatment of the same key, which is why the attempt
        // is in it: a re-run reusing the first run's key has its charges discarded by
        // ON CONFLICT DO NOTHING, and an auto-retry taking a new one charges one paid call twice.
        assertEquals("run:run::github:a/b:s:1:1:total", CallRefs.forRun("run::github:a/b:s:1", 1, "total"));
        assertEquals("run:run::github:a/b:s:1:2:total", CallRefs.forRun("run::github:a/b:s:1", 2, "total"));
        assertThrows(IllegalArgumentException.class,
                () -> CallRefs.forRun("run::github:a/b:s:1", 0, "total"));
    }
}
