package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.scm.ThreadRef;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The receipt migrations as an upgrade, in an isolated schema (the AccountWorkspaceDropMigrationTest pattern): history
 * is written at V109, before the receipt table exists, then V110 to V113 run and the real {@link ReviewThreadView}
 * replays old answers (review of PR #371, code-quality/M-1). Thread T counted A then B, each with its answer row; thread
 * U counted D then E, but D's answer row was never written and its FollowUpPosted waits in the dead-letter queue.
 */
@QuarkusTest
class ReviewThreadTurnUpgradeTest {

    private static final String REVIEW = "review::TEST-UPGRADE/TEST-REPO#1";
    private static final ThreadRef T = new ThreadRef("TEST-T");
    private static final ThreadRef U = new ThreadRef("TEST-U");
    private static final ThreadRef PRE_V24 = new ThreadRef("300");
    private static final ThreadRef LATE = new ThreadRef("410");
    private static final ThreadRef REPLAYED = new ThreadRef("500");

    @Inject
    DataSource dataSource;

    private String schema;
    private ReviewThreadView threads;

    @BeforeEach
    void upgradeFromHistoryWrittenAtV109() throws SQLException {
        schema = "test_turn_receipts_" + UUID.randomUUID().toString().replace("-", "");
        flyway().target("109").load().migrate();
        threads = new ReviewThreadView();
        threads.dataSource = inSchema(dataSource, schema);
        seed();
        flyway().load().migrate();
    }

    @AfterEach
    void dropTheSchema() {
        flyway().cleanDisabled(false).load().clean();
    }

    @Test
    void aLinkedOldAnswerReplayedCountsNothing() throws SQLException {
        threads.bumpTurn(REVIEW, T, "TEST-A");

        assertEquals(2, threads.turnCount(REVIEW, T));
        assertEquals("TEST-B", lastAnswer(T), "the replay does not move the last answer back");
    }

    /** D left no row naming it; its dead letter, replayed, is treated as counted (V112). */
    @Test
    void anUnlinkedDeadLetteredAnswerReplayedCountsNothing() throws SQLException {
        threads.bumpTurn(REVIEW, U, "TEST-D");

        assertEquals(2, threads.turnCount(REVIEW, U));
        assertEquals("TEST-E", lastAnswer(U));
    }

    @Test
    void aNewAnswerAfterTheUpgradeIsANewTurn() {
        threads.bumpTurn(REVIEW, T, "TEST-C");

        assertEquals(3, threads.turnCount(REVIEW, T));
    }

    /**
     * (a) Counted before V24, so neither A nor B has an answer row, and no dead letter: only the last answer is known.
     * The per-thread cutoff (V113) stops A, B, A counting a third turn; a genuinely new answer still counts.
     */
    @Test
    void anUnlinkedAnswerFromBeforeV24CountsNothingAndANewOneCounts() throws SQLException {
        threads.bumpTurn(REVIEW, PRE_V24, "310");
        threads.bumpTurn(REVIEW, PRE_V24, "320");
        threads.bumpTurn(REVIEW, PRE_V24, "310");

        assertEquals(2, threads.turnCount(REVIEW, PRE_V24));
        assertEquals("320", lastAnswer(PRE_V24));
        threads.bumpTurn(REVIEW, PRE_V24, "330");
        assertEquals(3, threads.turnCount(REVIEW, PRE_V24), "posted after the deploy, so its id is above the cutoff");
    }

    /**
     * (b) A's dead letter reaches dlq_entry only after the migration, and (c) another was already marked replayed
     * before it: neither is in V112's snapshot, and the cutoff stops both counting again.
     */
    @Test
    void aDeadLetterThatArrivesLateOrWasAlreadyReplayedCountsNothing() throws SQLException {
        exec("INSERT INTO dlq_entry (id, message_type, original_topic, payload) VALUES (gen_random_uuid(),"
                + " 'FollowUpPosted', 'cs.results', '{\"type\":\"FollowUpPosted\",\"reviewId\":\"' || ? || '\","
                + "\"threadRef\":{\"value\":\"410\"},\"commentId\":\"420\"}')");

        threads.bumpTurn(REVIEW, LATE, "420");
        threads.bumpTurn(REVIEW, REPLAYED, "510");

        assertEquals(2, threads.turnCount(REVIEW, LATE));
        assertEquals("430", lastAnswer(LATE));
        assertEquals(2, threads.turnCount(REVIEW, REPLAYED));
        assertEquals("530", lastAnswer(REPLAYED));
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
        return Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema);
    }

    /** What V109 knew: counts, last answers and answer rows, and one dead letter. No receipts: their table is V110's. */
    private void seed() throws SQLException {
        exec("INSERT INTO review_thread (review_id, thread_ref, turn_count, last_comment_id, is_ours)"
                + " VALUES (?, 'TEST-T', 2, 'TEST-B', TRUE), (?, 'TEST-U', 2, 'TEST-E', TRUE)");
        exec("INSERT INTO review_thread (review_id, thread_ref, root_ref, is_ours)"
                + " VALUES (?, 'TEST-A', 'TEST-T', TRUE), (?, 'TEST-B', 'TEST-T', TRUE)");
        exec("INSERT INTO dlq_entry (id, message_type, original_topic, payload) VALUES (gen_random_uuid(), 'FollowUpPosted',"
                + " 'cs.results', '{\"type\":\"FollowUpPosted\",\"reviewId\":\"' || ? || '\","
                + "\"threadRef\":{\"value\":\"TEST-U\"},\"commentId\":\"TEST-D\"}')");
        exec("INSERT INTO dlq_entry (id, message_type, original_topic, payload)"
                + " VALUES (gen_random_uuid(), 'FollowUpPosted', 'cs.results', 'TEST-not-json ' || ?)");
        seedNumericThreads();
    }

    /** Forge-shaped ids, which only grow: three threads that each counted two answers and recorded only the last. */
    private void seedNumericThreads() throws SQLException {
        exec("INSERT INTO review_thread (review_id, thread_ref, turn_count, last_comment_id, is_ours) VALUES"
                + " (?, '300', 2, '320', TRUE), (?, '410', 2, '430', TRUE), (?, '500', 2, '530', TRUE)");
        exec("INSERT INTO dlq_entry (id, message_type, original_topic, payload, status) VALUES (gen_random_uuid(),"
                + " 'FollowUpPosted', 'cs.results', '{\"type\":\"FollowUpPosted\",\"reviewId\":\"' || ? || '\","
                + "\"threadRef\":{\"value\":\"500\"},\"commentId\":\"510\"}', 'replayed')");
    }

    private void exec(String sql) throws SQLException {
        try (Connection c = threads.dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 1; i <= ps.getParameterMetaData().getParameterCount(); i++) {
                ps.setString(i, REVIEW);
            }
            ps.executeUpdate();
        }
    }

    private String lastAnswer(ThreadRef thread) throws SQLException {
        try (Connection c = threads.dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT last_comment_id FROM review_thread WHERE review_id = ? AND thread_ref = ?")) {
            ps.setString(1, REVIEW);
            ps.setString(2, thread.value());
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString(1);
            }
        }
    }

    /** Connections that work in {@code schema}, and give the pool back its own schema when closed. */
    private static DataSource inSchema(DataSource pool, String schema) {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    Object out = invoke(method, pool, args);
                    return out instanceof Connection connection ? scoped(connection, schema) : out;
                });
    }

    private static Connection scoped(Connection connection, String schema) throws SQLException {
        String previous = connection.getSchema();
        connection.setSchema(schema);
        return (Connection) Proxy.newProxyInstance(Connection.class.getClassLoader(), new Class<?>[] {Connection.class},
                (proxy, method, args) -> {
                    if ("close".equals(method.getName())) {
                        connection.setSchema(previous);
                    }
                    return invoke(method, connection, args);
                });
    }

    private static Object invoke(java.lang.reflect.Method method, Object target, Object[] args) throws Throwable {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException e) {
            throw e.getCause();
        }
    }
}
