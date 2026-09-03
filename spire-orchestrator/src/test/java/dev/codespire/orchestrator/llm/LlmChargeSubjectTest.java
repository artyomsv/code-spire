package dev.codespire.orchestrator.llm;

import dev.codespire.contract.review.TokenType;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

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

    @Inject
    ReviewProjection projection;

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
        // row. V42 finds it by definition instead.
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
    void aRunsChargesAreNotSummedIntoAReview() {
        // The two id spaces are kept apart only by their prefixes, and the review reads used to
        // filter on subject_id alone. An explicit subject_kind filter is what makes that
        // structural rather than a property of how ids happen to be spelled today. The id below is
        // run-shaped BECAUSE ChargeCall now refuses a RUN charge whose subject is not -- so the case
        // under test is a review charge recorded against that same id, which is the collision the
        // filter has to survive.
        //
        // Asked of the PRODUCTION read, which the first version of this test did not do: it
        // inserted two rows and then ran its own SELECT carrying the same WHERE clause, so it
        // asserted that Postgres honours a filter the test itself wrote and stayed green with the
        // filter deleted from costOf. Until this change nothing wrote a RUN row through the
        // production writer, so that vacuity was the only thing between a run's spend and some
        // unrelated pull request's cost card.
        String sharedId = "run::github:TEST-acme/app:shared-" + UUID.randomUUID() + ":1";

        projection.recordCharges(ChargeCall.forRun(sharedId, "CANARY-RUN-" + sharedId, "TEST-MODEL",
                List.of(ChargeLine.metered(TokenType.INPUT, 1_000_000, 999_000L)), null));
        projection.recordCharges(ChargeCall.forReview(sharedId, "CANARY-REVIEW-" + sharedId,
                ChargeKind.REVIEW, "TEST-MODEL",
                List.of(ChargeLine.metered(TokenType.INPUT, 1_000_000, 1_000L))));

        assertEquals(1_000L, projection.costOf(sharedId).knownCostMillicents(),
                "the run's spend must not reach a review's cost card, and the filter keeping them"
                        + " apart has to be the one production actually reads through");
    }

    @Test
    void bothChargesReallyReachedTheLedgerUnderTheSameSubjectId() {
        // The other half. Without it the assertion above would also pass if the run's charge had
        // simply not been written — which is how a filter test becomes a test of nothing.
        String sharedId = "run::github:TEST-acme/app:shared-" + UUID.randomUUID() + ":1";
        projection.recordCharges(ChargeCall.forRun(sharedId, "CANARY-RUN-" + sharedId, "TEST-MODEL",
                List.of(ChargeLine.metered(TokenType.INPUT, 1_000_000, 999_000L)), null));
        projection.recordCharges(ChargeCall.forReview(sharedId, "CANARY-REVIEW-" + sharedId,
                ChargeKind.REVIEW, "TEST-MODEL",
                List.of(ChargeLine.metered(TokenType.INPUT, 1_000_000, 1_000L))));

        assertEquals(List.of("RUN", "REVIEW"), subjectKindsOf(sharedId));
    }

    /** Every subject kind stored under one id, in insertion order. */
    private List<String> subjectKindsOf(String subjectId) {
        String sql = "SELECT subject_kind FROM llm_charge WHERE subject_id = ? ORDER BY priced_at, kind DESC";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, subjectId);
            try (ResultSet rs = ps.executeQuery()) {
                List<String> kinds = new ArrayList<>();
                while (rs.next()) {
                    kinds.add(rs.getString(1));
                }
                return kinds;
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read the charge rows back", e);
        }
    }

    @Test
    void aRunsChargeKeyDistinguishesARerunFromARedelivery() {
        // Re-run and auto-retry need OPPOSITE treatment of the same key, which is why the attempt
        // is in it: a re-run reusing the first run's key has its charges discarded by
        // ON CONFLICT DO NOTHING, and an auto-retry taking a new one charges one paid call twice.
        // The attempt is the run id's last field, so two attempts are two run ids and two keys
        // without the key carrying the attempt a second time.
        assertEquals("run:run::github:a/b:s:1:total", CallRefs.forRun("run::github:a/b:s:1", "total"));
        assertEquals("run:run::github:a/b:s:2:total", CallRefs.forRun("run::github:a/b:s:2", "total"));
        assertThrows(IllegalArgumentException.class, () -> CallRefs.forRun(" ", "total"));
        assertThrows(IllegalArgumentException.class, () -> CallRefs.forRun("run::github:a/b:s:1", ""));
    }
}
