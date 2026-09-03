package dev.codespire.orchestrator.factory;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The keys a factory run may call the model with, and the order they are handed out in (FR-F12).
 *
 * <p>The pool exists because a run's failure mode is exhaustion: an agent works for an hour and can
 * run a key's quota out mid-run, and the runs behind it must not queue up behind a dead credential.
 * Its two exhaustion states are the part worth testing hardest — a rate limit is a promise that
 * capacity returns, a rejection is an answer that it will not, and collapsing them means either
 * retrying a dead key for ever or treating a five-minute pause as permanent.
 */
@QuarkusTest
class HarnessCredentialPoolTest {

    @Inject
    HarnessCredentialPool pool;

    @Inject
    DataSource dataSource;

    /**
     * Only this suite's members are enabled, so a selection assertion is about what this test put in
     * the pool. Disabled rather than deleted: another suite's run rows hold their members by foreign
     * key, and a DELETE here would fail — which is the schema keeping a finished run's attribution
     * from vanishing with the key.
     */
    @BeforeEach
    void onlyOurMembers() {
        sql("UPDATE harness_credential SET enabled = FALSE");
    }

    /**
     * Leave no exhausted pool behind.
     *
     * <p>A test ending with one enabled, rejected member raises a global
     * {@code HARNESS_POOL_EXHAUSTED} row for every suite that runs afterwards. Nothing resets it,
     * and today every attention assertion in this codebase is a {@code hasItem} — so the first
     * strict-count assertion anyone adds goes flaky by class ordering.
     */
    @AfterEach
    void leaveNoExhaustedPool() {
        sql("UPDATE harness_credential SET enabled = FALSE");
    }

    private UUID member(String suffix) {
        return pool.add("TEST-pool-" + suffix + "-" + UUID.randomUUID(), "openai",
                "https://api.openai.com", "TEST-agent-key-" + suffix).id();
    }

    private HarnessCredentialPool.MemberView view(UUID id) {
        return pool.list().stream().filter(m -> m.id().equals(id)).findFirst().orElseThrow();
    }

    private UUID chosen() {
        return assertInstanceOf(HarnessCredentialPool.Selection.Chosen.class, pool.select()).member().id();
    }

    /**
     * Rotation is by REST, not by recency of use.
     *
     * <p>Ordering by use alone would send the next run straight back at the key that just ran out.
     * A member that was exhausted an hour ago is a better bet than one exhausted a minute ago, and a
     * member that has never been exhausted is the best bet of all.
     */
    @Test
    void theLeastRecentlyExhaustedMemberIsChosen() {
        UUID exhaustedLongAgo = member("old");
        UUID exhaustedRecently = member("recent");
        // Both have recovered, so both are selectable, and the two orderings are set to DISAGREE:
        // by rest the answer is exhaustedLongAgo, by use it is exhaustedRecently. A mutation proved
        // this necessary — with both last_used_at left NULL, ordering by use alone is a tie that
        // Postgres broke the way this test expected, so the assertion held with the rest ordering
        // deleted and proved nothing at all.
        sql("UPDATE harness_credential SET exhausted_at = now() - interval '2 hours',"
                + " last_used_at = now() - interval '1 minute' WHERE id = '" + exhaustedLongAgo + "'");
        sql("UPDATE harness_credential SET exhausted_at = now() - interval '1 minute',"
                + " last_used_at = now() - interval '2 hours' WHERE id = '" + exhaustedRecently + "'");

        assertEquals(exhaustedLongAgo, chosen(),
                "the member rested longest is the one handed out, not the one used least recently");
    }

    @Test
    void aMemberThatHasNeverBeenExhaustedIsPreferredToOneThatHas() {
        UUID everExhausted = member("used-up");
        UUID fresh = member("fresh");
        // Again set so the two orderings disagree: the never-exhausted member is the MORE recently
        // used one, so only the rest ordering picks it.
        sql("UPDATE harness_credential SET exhausted_at = now() - interval '3 hours',"
                + " last_used_at = now() - interval '3 hours' WHERE id = '" + everExhausted + "'");
        sql("UPDATE harness_credential SET last_used_at = now() - interval '1 minute' WHERE id = '"
                + fresh + "'");

        assertEquals(fresh, chosen());
    }

    /**
     * The two states differ in exactly one thing that matters: whether anything brings the member
     * back without an operator.
     */
    @Test
    void rateLimitedAndRejectedAreDifferentStates() {
        UUID rateLimited = member("limited");
        pool.markRateLimited(rateLimited, Instant.now().plus(1, ChronoUnit.HOURS));
        assertInstanceOf(HarnessCredentialPool.Selection.Resting.class, pool.select(),
                "a rate limit is a promise: the pool says when capacity returns");

        sql("UPDATE harness_credential SET enabled = FALSE");
        UUID rejected = member("refused");
        pool.markRejected(rejected);
        assertInstanceOf(HarnessCredentialPool.Selection.AllRejected.class, pool.select(),
                "a rejection is an answer: nothing returns, and the refusal must not quote a time");
    }

    @Test
    void aRateLimitedMemberReturnsWhenItsWindowPasses() {
        UUID id = member("returning");
        pool.markRateLimited(id, Instant.now().plus(1, ChronoUnit.HOURS));
        assertInstanceOf(HarnessCredentialPool.Selection.Resting.class, pool.select());

        // The window passes. Nothing runs, nothing is cleared -- the pool heals itself, which is the
        // whole difference between this state and a rejection.
        sql("UPDATE harness_credential SET rate_limited_until = now() - interval '1 minute' WHERE id = '"
                + id + "'");

        assertEquals(id, chosen());
    }

    /**
     * A rejected key stays out until an operator acts, and the reason is money: rotating onto it
     * spends one paid run per attempt to rediscover it is dead.
     */
    @Test
    void aRejectedMemberIsNotRetriedUntilAnOperatorActs() {
        UUID id = member("dead");
        pool.markRejected(id);

        assertInstanceOf(HarnessCredentialPool.Selection.AllRejected.class, pool.select());
        // No amount of time brings it back -- there is no expiry column to age out.
        sql("UPDATE harness_credential SET rejected_at = now() - interval '30 days' WHERE id = '" + id + "'");
        assertInstanceOf(HarnessCredentialPool.Selection.AllRejected.class, pool.select());

        assertTrue(pool.clearRejection(id), "the operator's action is the only way back");
        assertEquals(id, chosen());
    }

    @Test
    void clearingAMemberThatWasNeverRejectedChangesNothing() {
        // The half that keeps the operator action from being unconditional: it must report that it
        // found nothing to clear rather than answering success for a healthy member.
        UUID healthy = member("healthy");

        assertFalse(pool.clearRejection(healthy));
    }

    /**
     * An exhausted pool says WHICH kind of exhausted, because the three answers need three different
     * actions — wait, replace a key, or configure one at all.
     */
    @Test
    void exhaustingThePoolIsAFirstClassRefusalNamingWhatToDo() {
        assertInstanceOf(HarnessCredentialPool.Selection.Empty.class, pool.select(),
                "no members at all is 'configure one', not 'wait'");

        UUID resting = member("resting");
        UUID dead = member("dead");
        pool.markRateLimited(resting, Instant.now().plus(30, ChronoUnit.MINUTES));
        pool.markRejected(dead);

        HarnessCredentialPool.Selection selection = pool.select();
        HarnessCredentialPool.Selection.Resting rest =
                assertInstanceOf(HarnessCredentialPool.Selection.Resting.class, selection,
                        "a mixed pool reports the recoverable half: capacity DOES return, and an "
                                + "operator told only 'all rejected' would replace keys unnecessarily");
        assertTrue(rest.capacityReturnsAt().isAfter(Instant.now()));
    }

    @Test
    void aRejectedMemberIsNeverHandedOutWhileAHealthyOneExists() {
        UUID dead = member("dead");
        UUID healthy = member("healthy");
        pool.markRejected(dead);
        // The rejected member is made the one the ROTATION would otherwise prefer, so only the
        // exclusion can produce the expected answer. A mutation proved this necessary: markRejected
        // stamps exhausted_at, so with both left as they were the rest ordering picked `healthy`
        // whether or not rejected members were filtered out, and the test held with the filter
        // deleted from select().
        sql("UPDATE harness_credential SET exhausted_at = NULL WHERE id = '" + dead + "'");
        sql("UPDATE harness_credential SET exhausted_at = now() - interval '1 minute' WHERE id = '"
                + healthy + "'");

        assertEquals(healthy, chosen());
        assertEquals(healthy, chosen(), "and again -- a rejection is not a rotation slot");
    }

    /**
     * The everyday rotation, which nothing asserted.
     *
     * <p>Both existing rotation tests set {@code exhausted_at}, so they only ever exercise the
     * PRIMARY sort key. A healthy pool — nothing ever exhausted — rotates entirely on the
     * {@code last_used_at} tiebreaker, and that is the normal state of a working deployment. A
     * mutation dropping the tiebreaker killed no test.
     */
    @Test
    void aHealthyPoolRotatesRatherThanReturningTheSameMember() {
        member("a");
        member("b");

        UUID first = chosen();
        UUID second = chosen();

        assertNotEquals(first, second,
                "two never-exhausted members must alternate; without the last_used_at tiebreaker the"
                        + " pool hands out the same key every time");
        assertEquals(first, chosen(), "and comes back round rather than drifting");
    }

    /**
     * Resting an already-refused member is refused, not silently ignored.
     *
     * <p>The {@code AND rejected_at IS NULL} guard is what keeps the V52 CHECK from being violated
     * — a row cannot carry both states. A mutation removing it killed no test, and without it the
     * same call becomes a constraint violation and a bare 500.
     */
    @Test
    void anAlreadyRefusedMemberCannotBeRested() {
        UUID dead = member("dead");
        pool.markRejected(dead);

        assertFalse(pool.markRateLimited(dead, Instant.now().plus(1, ChronoUnit.HOURS)),
                "reporting success here would assert a rest that the CHECK forbids and the write"
                        + " never performed");
        assertTrue(view(dead).rejectedAt() != null, "and the rejection stands");
        assertEquals(null, view(dead).rateLimitedUntil());
    }

    @Test
    void restingAnUnknownMemberReportsThatItFoundNothing() {
        assertFalse(pool.markRateLimited(UUID.randomUUID(), null));
    }

    /** Disabling is not deletion, so it must not be one-way. */
    @Test
    void aDisabledMemberCanBeBroughtBack() {
        UUID id = member("returning");
        assertTrue(pool.remove(id));
        assertInstanceOf(HarnessCredentialPool.Selection.Empty.class, pool.select());

        assertTrue(pool.enable(id));

        assertEquals(id, chosen());
        assertFalse(pool.enable(id), "an already-enabled member reports that nothing changed");
    }

    /**
     * A mixed pool says BOTH halves, because they need different actions.
     *
     * <p>The refusal used to say only "every credential is rate limited, retry then", concealing
     * that N of the operator's keys were permanently dead. The attention row said it and the
     * dispatch refusal did not — one predicate, two surfaces, already drifted.
     */
    @Test
    void aMixedPoolReportsThePermanentlyLostHalfAsWellAsTheWait() {
        UUID resting = member("resting");
        UUID dead = member("dead");
        pool.markRateLimited(resting, Instant.now().plus(30, ChronoUnit.MINUTES));
        pool.markRejected(dead);

        HarnessCredentialPool.Selection.Resting rest = assertInstanceOf(
                HarnessCredentialPool.Selection.Resting.class, pool.select());

        assertEquals(1, rest.rejected(),
                "the wait is only half the story; one of these keys never comes back");
    }

    /**
     * A member cannot be resting and refused at once, and the schema says so.
     *
     * <p>The pairing is what keeps the two recovery rules apart: a row claiming both has no defined
     * return time, so the selector would have to guess — and the guess that reads it as a rate limit
     * retries a dead key for ever.
     */
    @Test
    void rejectingAMemberClearsItsRateLimitRatherThanStackingBoth() {
        UUID id = member("both");
        pool.markRateLimited(id, Instant.now().plus(1, ChronoUnit.HOURS));

        pool.markRejected(id);

        HarnessCredentialPool.MemberView view = pool.list().stream()
                .filter(m -> m.id().equals(id)).findFirst().orElseThrow();
        assertEquals(null, view.rateLimitedUntil(), "the V52 CHECK forbids both, so one must give way");
        assertTrue(view.rejectedAt() != null);
    }

    @Test
    void aRateLimitWithNoStatedWindowRestsForABoundedTimeRatherThanForEver() {
        // An unstated limit is still a promise that capacity returns. Leaving the member out
        // indefinitely would turn the recoverable state into the permanent one, which is the exact
        // collapse the two states exist to prevent.
        UUID id = member("unstated");

        pool.markRateLimited(id, null);

        HarnessCredentialPool.Selection.Resting rest = assertInstanceOf(
                HarnessCredentialPool.Selection.Resting.class, pool.select());
        assertTrue(rest.capacityReturnsAt().isAfter(Instant.now()));
        assertTrue(rest.capacityReturnsAt().isBefore(Instant.now().plus(2, ChronoUnit.HOURS)),
                "bounded, not indefinite");
    }

    @Test
    void aDisabledMemberIsNeverHandedOut() {
        UUID id = member("disabled");
        assertTrue(pool.remove(id));

        assertInstanceOf(HarnessCredentialPool.Selection.Empty.class, pool.select());
    }

    /**
     * A key that cannot be decrypted retires itself.
     *
     * <p>{@code EncryptionService} throws {@code IllegalStateException}, not {@code SQLException},
     * so this used to escape the selector as a bare 500 — with {@code last_used_at} already
     * committed, the member still in the pool, and nothing naming which one. It came round again
     * every rotation: a permanently intermittent failure with no diagnostic. Reachable whenever the
     * keyset rotates or a row is restored from a backup taken under a different one.
     */
    @Test
    void aMemberWhoseKeyCannotBeDecryptedLeavesThePoolInsteadOfFailingForever() {
        UUID broken = member("broken");
        UUID healthy = member("healthy");
        // Ciphertext that is not ours: the AAD binding cannot verify, so decryption fails the way a
        // rotated keyset would.
        sql("UPDATE harness_credential SET api_key = 'not-a-tink-ciphertext' WHERE id = '"
                + broken + "'");
        sql("UPDATE harness_credential SET exhausted_at = now() WHERE id = '" + healthy + "'");

        // The broken one sorts first, so it is what select() reaches.
        HarnessCredentialPool.Selection selection = pool.select();

        assertTrue(view(broken).rejectedAt() != null,
                "it must leave the pool; leaving it in makes one dispatch in N fail for ever");
        assertInstanceOf(HarnessCredentialPool.Selection.Resting.class, selection,
                "and the caller is told why nothing was handed out, not handed a broken member");
        assertEquals(healthy, chosen(), "the healthy member still serves");
    }

    @Test
    void theKeyIsEncryptedAtRestAndComesBackDecrypted() {
        // A raw read must not yield the key: every registry in this codebase stores it Tink-encrypted
        // with the AAD bound to its own row, and a selection that returned ciphertext would hand the
        // agent something that cannot call a model.
        UUID id = member("crypto");

        assertEquals("TEST-agent-key-crypto", assertInstanceOf(
                HarnessCredentialPool.Selection.Chosen.class, pool.select()).member().apiKey());
        assertFalse(column(id, "api_key").contains("TEST-agent-key-crypto"),
                "the stored form is ciphertext, not the key");
    }

    private String column(UUID id, String name) {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement();
             var rs = s.executeQuery("SELECT " + name + " FROM harness_credential WHERE id = '" + id + "'")) {
            return rs.next() ? rs.getString(1) : "";
        } catch (SQLException e) {
            throw new IllegalStateException("read failed", e);
        }
    }

    private void sql(String statement) {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate(statement);
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + statement, e);
        }
    }
}
