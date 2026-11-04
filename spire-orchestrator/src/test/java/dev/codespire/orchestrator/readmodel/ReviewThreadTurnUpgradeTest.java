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
 * is written at V109, before the receipt table exists, then V110 to V114 run and the real {@link ReviewThreadView}
 * takes the old answers back as {@code ResultSaga} hands them to it (review of PR #371, code-quality/M-1): a
 * {@code FollowUpPosted} recorded before the receipts has no {@code countsTurn} and is filed ({@code fileAnswer}); one
 * posted since is counted ({@code bumpTurn}). Whatever an old thread's history looks like and in whatever order its
 * answers come back, its count and last answer stay exactly as they were, and a new answer counts once.
 */
@QuarkusTest
class ReviewThreadTurnUpgradeTest {

    private static final String REVIEW = "review::TEST-UPGRADE/TEST-REPO#1";
    /** A then B, each with its own answer row: V111 finds both. */
    private static final ThreadRef LINKED = new ThreadRef("TEST-T");
    /** D then E; D's answer row was never written and its event waits in the dead-letter queue: V112 finds it. */
    private static final ThreadRef DEAD_LETTERED = new ThreadRef("TEST-U");
    /**
     * Counted before V24, so no answer rows, and 320 was counted before the delayed 310: the last answer is the LOWER
     * id, the shape V113's greatest-surviving-id cutoff got wrong (a replay of 320 counted a third turn).
     */
    private static final ThreadRef REVERSED = new ThreadRef("300");
    /** Replays had already inflated the count: 610, 620, 610 counted three turns and left the last answer at 610. */
    private static final ThreadRef INFLATED = new ThreadRef("600");

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
    void aLinkedHistoryIsUnchangedByItsAnswersComingBackInAnyOrder() throws SQLException {
        cameBack(LINKED, "TEST-B", "TEST-A", "TEST-B");

        assertUnchanged(LINKED, 2, "TEST-B");
    }

    @Test
    void aDeadLetteredAnswerWithNoRowComingBackChangesNothing() throws SQLException {
        cameBack(DEAD_LETTERED, "TEST-D", "TEST-E");

        assertUnchanged(DEAD_LETTERED, 2, "TEST-E");
    }

    @Test
    void aHigherIdThanTheLastAnswerComingBackChangesNothing() throws SQLException {
        cameBack(REVERSED, "320", "310", "320");

        assertUnchanged(REVERSED, 2, "310");
    }

    /** The count is preserved as it was, not repaired to the two answers it should have been. */
    @Test
    void anAlreadyInflatedCountIsPreservedNotRepaired() throws SQLException {
        cameBack(INFLATED, "620", "610", "620");

        assertUnchanged(INFLATED, 3, "610");
    }

    @Test
    void aNewAnswerAfterTheUpgradeCountsOnceAndBecomesTheLast() throws SQLException {
        cameBack(REVERSED, "320"); // an old answer may land first, as a replay would

        threads.bumpTurn(REVIEW, REVERSED, "330");
        threads.bumpTurn(REVIEW, REVERSED, "330"); // the harness worker resends until its events are known out

        assertUnchanged(REVERSED, 3, "330");
    }

    /** The control: taken as a counting answer, the same old id counts again, so the tests above rest on the routing. */
    @Test
    void anOldAnswerTakenAsCountingWouldCountAgain() throws SQLException {
        threads.bumpTurn(REVIEW, REVERSED, "320");

        assertUnchanged(REVERSED, 3, "320");
    }

    /** Old answers back by any path, as ResultSaga files a FollowUpPosted that does not say countsTurn. */
    private void cameBack(ThreadRef thread, String... answers) {
        for (String answer : answers) {
            threads.fileAnswer(REVIEW, thread, answer);
        }
    }

    private void assertUnchanged(ThreadRef thread, int count, String last) throws SQLException {
        assertEquals(count, threads.turnCount(REVIEW, thread), "the turn count of " + thread.value());
        assertEquals(last, lastAnswer(thread), "the last answer of " + thread.value());
    }

    private org.flywaydb.core.api.configuration.FluentConfiguration flyway() {
        return Flyway.configure().dataSource(dataSource).schemas(schema).defaultSchema(schema);
    }

    /** What V109 knew: counts, last answers, answer rows and dead letters. No receipts: their table is V110's. */
    private void seed() throws SQLException {
        exec("INSERT INTO review_thread (review_id, thread_ref, turn_count, last_comment_id, is_ours) VALUES"
                + " (?, 'TEST-T', 2, 'TEST-B', TRUE), (?, 'TEST-U', 2, 'TEST-E', TRUE),"
                + " (?, '300', 2, '310', TRUE), (?, '600', 3, '610', TRUE)");
        exec("INSERT INTO review_thread (review_id, thread_ref, root_ref, is_ours)"
                + " VALUES (?, 'TEST-A', 'TEST-T', TRUE), (?, 'TEST-B', 'TEST-T', TRUE)");
        exec("INSERT INTO dlq_entry (id, message_type, original_topic, payload) VALUES (gen_random_uuid(), 'FollowUpPosted',"
                + " 'cs.results', '{\"type\":\"FollowUpPosted\",\"reviewId\":\"' || ? || '\","
                + "\"threadRef\":{\"value\":\"TEST-U\"},\"commentId\":\"TEST-D\"}')");
        exec("INSERT INTO dlq_entry (id, message_type, original_topic, payload)"
                + " VALUES (gen_random_uuid(), 'FollowUpPosted', 'cs.results', 'TEST-not-json ' || ?)");
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
