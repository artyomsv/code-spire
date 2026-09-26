package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.HarnessSignInResult;
import dev.codespire.encryption.EncryptionService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Signing a harness in with a subscription, from the operator's side (M3.5 part F).
 *
 * <p>The rules worth testing hardest are the ones that decide what ends up in the POOL. A pending
 * sign-in must never be selectable, a sign-in that came back as an API key must not be stored as a
 * subscription, and one sign-in must never produce two members — two members holding one seat is a
 * state the lease cannot reconcile and nothing afterwards can repair.
 */
@QuarkusTest
class HarnessSignInsTest {

    @Inject HarnessSignIns signIns;
    @Inject HarnessCredentialPool pool;
    @Inject EncryptionService encryption;
    @Inject DataSource dataSource;

    private static final String HARNESS = "codex";

    /** A subscription-shaped file. The mode is NOT the measured API-key one, which is all that matters. */
    private static final String SUBSCRIPTION_FILE = subscriptionFile("TEST-account-" + UUID.randomUUID(), "TEST-not-a-real-token");

    private static String subscriptionFile(String account, String token) {
        return "{\"auth_mode\":\"TEST-chatgpt\",\"tokens\":{\"access\":\"" + token + "\",\"account_id\":\"" + account + "\"}}";
    }

    /** The measured API-key file, with an obviously fake key. */
    private static final String API_KEY_FILE = "{\"auth_mode\":\"apikey\",\"OPENAI_API_KEY\":\"sk-TEST-not-real\"}";

    /** Only what this class made, named so no other suite's rows are in range. */
    private static final String OWNED = "TEST-seat-%";

    @AfterEach
    void clean() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM harness_sign_in WHERE label LIKE '" + OWNED + "'");
            // A credential a factory_run points at cannot be deleted: the foreign key has no ON DELETE,
            // by design, so the attribution a finished run carries survives. Nothing here dispatches a
            // run, so this deletes only rows nothing references — and says so rather than forcing it.
            s.executeUpdate("DELETE FROM harness_credential WHERE label LIKE '" + OWNED + "'"
                    + " AND id NOT IN (SELECT harness_credential_id FROM factory_run"
                    + " WHERE harness_credential_id IS NOT NULL)");
        }
    }

    private HarnessSignIns.View start(String label) {
        HarnessSignIns.Started started = signIns.start(label, HARNESS, "TEST-operator");
        assertNull(started.refusal(), started.refusal());
        return started.view();
    }

    private HarnessSignInResult.Completed completion(UUID id, String body) {
        return new HarnessSignInResult.Completed(id.toString(),
                encryption.encryptString(body, HarnessSignInResult.sealedAad(id.toString())),
                SignInMode.of(body), SignInMode.of(body));
    }

    /** Reads the one field this build has measured, the way the worker does. */
    private static final class SignInMode {
        static String of(String body) {
            java.util.regex.Matcher match = java.util.regex.Pattern
                    .compile("\"auth_mode\"\\s*:\\s*\"([^\"]+)\"").matcher(body);
            return match.find() ? match.group(1) : "unknown";
        }
    }

    @Test
    void aStartedSignInIsPendingAndIsNotYetAPoolMember() {
        HarnessSignIns.View view = start("TEST-seat-pending");

        assertEquals("PENDING", view.state());
        assertNull(view.credentialId());
        // The pool must not see it. A member with no secret is one a run can select and then fail on.
        assertTrue(pool.list().stream().noneMatch(member -> member.label().equals("TEST-seat-pending")));
    }

    @Test
    void theLinkAndCodeReachTheScreen() {
        HarnessSignIns.View view = start("TEST-seat-prompt");
        Instant expires = Instant.now().plusSeconds(600);

        signIns.prompted(new HarnessSignInResult.Prompted(view.id().toString(),
                "https://auth.example.test/device", "ABCD-12345", expires));

        HarnessSignIns.View prompted = signIns.get(view.id()).orElseThrow();
        assertEquals("PROMPTED", prompted.state());
        assertEquals("https://auth.example.test/device", prompted.verificationUri());
        assertEquals("ABCD-12345", prompted.userCode());
        assertNotNull(prompted.expiresAt());
    }

    @Test
    void anApprovedSignInBecomesASubscriptionMemberUnderTheNameTheOperatorGaveIt() {
        HarnessSignIns.View view = start("TEST-seat-complete");

        signIns.completed(completion(view.id(), SUBSCRIPTION_FILE));

        assertEquals("COMPLETE", signIns.get(view.id()).orElseThrow().state());
        var member = pool.list().stream().filter(row -> row.label().equals("TEST-seat-complete")).findFirst().orElseThrow();
        assertNotNull(member.id());
        // A subscription has no endpoint to override; inventing one puts a value where something later
        // reads configuration.
        assertEquals("", member.baseUrl());
    }

    /**
     * Signing in again to an account that has a seat renews that seat's file instead of adding a second
     * seat — the way an expired seat is renewed (review of PR #178).
     */
    @Test
    void aSecondSignInToTheSameAccountRenewsItsSeat() throws SQLException {
        String account = "TEST-account-" + UUID.randomUUID();
        HarnessSignIns.View first = start("TEST-seat-renew-first");
        signIns.completed(completion(first.id(), subscriptionFile(account, "TEST-old-token")));
        UUID seat = signIns.get(first.id()).orElseThrow().credentialId();

        HarnessSignIns.View second = start("TEST-seat-renew-second");
        signIns.completed(completion(second.id(), subscriptionFile(account, "TEST-new-token")));

        HarnessSignIns.View renewed = signIns.get(second.id()).orElseThrow();
        assertEquals("COMPLETE", renewed.state());
        assertEquals(seat, renewed.credentialId(), "the same seat, not a second one");
        assertTrue(pool.list().stream().noneMatch(member -> member.label().equals("TEST-seat-renew-second")));
        try (Connection c = dataSource.getConnection();
             java.sql.PreparedStatement ps = c.prepareStatement("SELECT api_key FROM harness_credential WHERE id = ?")) {
            ps.setObject(1, seat);
            try (java.sql.ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertTrue(encryption.decryptString(rs.getString(1), "harness-credential:" + seat).contains("TEST-new-token"));
            }
        }
    }

    @Test
    void aSignInThatNamesNoAccountIsRefused() {
        HarnessSignIns.View view = start("TEST-seat-no-account");

        signIns.completed(completion(view.id(), "{\"auth_mode\":\"TEST-chatgpt\",\"tokens\":{\"access\":\"TEST-token\"}}"));

        HarnessSignIns.View after = signIns.get(view.id()).orElseThrow();
        assertEquals("FAILED", after.state());
        assertEquals("subscription_unidentified", after.reason());
        assertTrue(pool.list().stream().noneMatch(member -> member.label().equals("TEST-seat-no-account")));
    }

    /**
     * A sign-in that came back as an API key is refused rather than stored as a subscription.
     *
     * <p>A subscription member that is really a key would be selected for subscription work and then
     * bill per token — the exact outcome this part exists to prevent, and one nothing downstream could
     * detect, because both look like a pool member afterwards.
     */
    @Test
    void anApiKeySignInIsRefusedRatherThanStoredAsASubscription() {
        HarnessSignIns.View view = start("TEST-seat-wrong-mode");

        signIns.completed(completion(view.id(), API_KEY_FILE));

        HarnessSignIns.View after = signIns.get(view.id()).orElseThrow();
        assertEquals("FAILED", after.state());
        assertEquals(HarnessSignInResult.Failed.WRONG_MODE, after.reason());
        assertTrue(pool.list().stream().noneMatch(member -> member.label().equals("TEST-seat-wrong-mode")));
    }

    /**
     * A stored subscription is INVISIBLE to a run, and that is the boundary this whole part rests on.
     *
     * <p>Without it, completing the sign-in screen was enough to break the next factory run and hand
     * the entire sign-in file — refresh credential included — to a container that runs untrusted ticket
     * text at full shell access. The harness arm pipes whatever the pool returns into
     * {@code --with-api-key}; it cannot tell the two apart, and nothing downstream could.
     *
     * <p>No selection, injection or charging for a subscription exists yet. Until all three do, a run
     * must not be able to reach one at all.
     */
    @Test
    void aStoredSubscriptionIsNeverHandedToARun() throws SQLException {
        HarnessSignIns.View view = start("TEST-seat-isolated");
        signIns.completed(completion(view.id(), SUBSCRIPTION_FILE));
        assertTrue(pool.list().stream().anyMatch(member -> member.label().equals("TEST-seat-isolated")),
                "it is in the pool and on the operator's screen");

        // Every OTHER member is rested for the duration, so the subscription is the only row a selector
        // that ignored the mode could possibly return. Other suites leave keys in this pool, and a test
        // that asked what the whole pool answers would be testing them.
        withNothingElseAvailable(() -> {
            var selection = pool.select();
            assertInstanceOf(HarnessCredentialPool.Selection.Empty.class, selection,
                    "a run must find NOTHING rather than a sign-in file dressed as a key: " + selection);
        });
    }

    /** And an API key beside it is still selected, or the rule above would be "select nothing, ever". */
    @Test
    void anApiKeyBesideASubscriptionIsStillTheOneARunGets() throws SQLException {
        HarnessSignIns.View view = start("TEST-seat-alongside");
        signIns.completed(completion(view.id(), SUBSCRIPTION_FILE));

        withNothingElseAvailable(() -> {
            pool.add("TEST-seat-real-key", "openai", "https://api.example.test", "sk-TEST-not-real");

            var selection = pool.select();

            var chosen = assertInstanceOf(HarnessCredentialPool.Selection.Chosen.class, selection).member();
            assertEquals("TEST-seat-real-key", chosen.label());
        });
    }

    /**
     * Runs the body with every member this class did not create switched off, then switches them back.
     *
     * <p>Only the ids actually changed are restored, so a member another suite had deliberately
     * disabled stays disabled.
     */
    private void withNothingElseAvailable(Runnable body) throws SQLException {
        List<UUID> quieted = new ArrayList<>();
        try (Connection c = dataSource.getConnection()) {
            try (Statement s = c.createStatement();
                 var rs = s.executeQuery("SELECT id FROM harness_credential WHERE enabled"
                         + " AND label NOT LIKE '" + OWNED + "'")) {
                while (rs.next()) quieted.add(rs.getObject(1, UUID.class));
            }
            for (UUID id : quieted) setEnabled(c, id, false);
        }
        try { body.run(); }
        finally {
            try (Connection c = dataSource.getConnection()) {
                for (UUID id : quieted) setEnabled(c, id, true);
            }
        }
    }

    private static void setEnabled(Connection c, UUID id, boolean enabled) throws SQLException {
        try (var ps = c.prepareStatement("UPDATE harness_credential SET enabled=? WHERE id=?")) {
            ps.setBoolean(1, enabled); ps.setObject(2, id); ps.executeUpdate();
        }
    }

    /**
     * A cancel and a completion racing: the cancel wins and STAYS won.
     *
     * <p>The completion read the state without a lock, so a cancel committing in between was simply
     * overwritten — the member was created and the row went back to COMPLETE, storing a credential the
     * operator had already declined. Both terminal decisions now queue on the same row.
     */
    @Test
    void aCompletionRacingACancelCannotStoreWhatWasDeclined() throws Exception {
        HarnessSignIns.View view = start("TEST-seat-race");
        var completion = completion(view.id(), SUBSCRIPTION_FILE);

        // The ORDER is forced, not hoped for. A free-running race passes under the old bug too: the
        // completion overwrote FAILED with COMPLETE and stored the member, so "a member exists exactly
        // when the row says COMPLETE" stayed true while the operator's cancel was being ignored.
        //
        // So the row is locked here first, the completion is started and blocks on that same lock, the
        // cancellation is written and committed, and only then is the completion let go. It must find
        // the decision already made.
        try (Connection held = dataSource.getConnection()) {
            held.setAutoCommit(false);
            try (var ps = held.prepareStatement("SELECT id FROM harness_sign_in WHERE id=? FOR UPDATE")) {
                ps.setObject(1, view.id());
                try (var rs = ps.executeQuery()) { assertTrue(rs.next()); }
            }
            try (var pool2 = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
                var completing = pool2.submit(() -> { signIns.completed(completion); return true; });
                Thread.sleep(500);
                assertFalse(completing.isDone(), "the completion must be waiting on the locked row");

                try (var ps = held.prepareStatement(
                        "UPDATE harness_sign_in SET state='FAILED', reason=? WHERE id=?")) {
                    ps.setString(1, HarnessSignInResult.Failed.CANCELLED); ps.setObject(2, view.id());
                    assertEquals(1, ps.executeUpdate());
                }
                held.commit();

                completing.get(30, java.util.concurrent.TimeUnit.SECONDS);
            }
        }

        HarnessSignIns.View after = signIns.get(view.id()).orElseThrow();
        assertEquals("FAILED", after.state(), "the cancellation was already committed and must stand");
        assertEquals(HarnessSignInResult.Failed.CANCELLED, after.reason(), "and with its own reason intact");
        assertTrue(pool.list().stream().noneMatch(member -> member.label().equals("TEST-seat-race")),
                "nothing the operator declined may be stored");
    }

    /**
     * Two requests for one harness at the same moment: exactly one opens.
     *
     * <p>The check was a read before an insert, which cannot exclude a concurrent one — two containers
     * would race for one seat and put two codes in front of one person. V78's unique partial index is
     * the authority, and its violation answers with the same refusal rather than a 500.
     */
    @Test
    void twoRequestsAtOnceOpenOnlyOneSignIn() throws Exception {
        try (var pool2 = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var first = pool2.submit(() -> signIns.start("TEST-seat-race-a", HARNESS, "TEST-operator"));
            var second = pool2.submit(() -> signIns.start("TEST-seat-race-b", HARNESS, "TEST-operator"));
            var outcomes = java.util.List.of(first.get(30, java.util.concurrent.TimeUnit.SECONDS),
                    second.get(30, java.util.concurrent.TimeUnit.SECONDS));

            assertEquals(1, outcomes.stream().filter(started -> started.view() != null).count(),
                    "exactly one may open: " + outcomes);
            assertTrue(outcomes.stream().filter(started -> started.view() == null)
                            .allMatch(started -> "sign_in_already_running".equals(started.refusal())),
                    "and the loser is refused by name, not by a 500: " + outcomes);
        }
    }

    /** A name taken while the operator was approving is refused by name, not lost in a swallowed throw. */
    @Test
    void aNameTakenWhileApprovingRefusesInsteadOfLosingTheCredential() {
        HarnessSignIns.View view = start("TEST-seat-stolen");
        pool.add("TEST-seat-stolen", "openai", "https://api.example.test", "sk-TEST-not-real");

        signIns.completed(completion(view.id(), SUBSCRIPTION_FILE));

        HarnessSignIns.View after = signIns.get(view.id()).orElseThrow();
        assertEquals("FAILED", after.state());
        assertEquals("harness_credential_label_taken", after.reason());
    }

    /**
     * The KIND is judged on the sealed bytes, never on what the result claimed beside them.
     *
     * <p>A result asserting a subscription while carrying an API-key file used to be believed. Stored,
     * that member is billed as an asserted zero while the vendor charges per token — and nothing
     * downstream could tell, because both look like a pool row.
     */
    @Test
    void aResultThatMisdescribesItsOwnBytesIsRefused() {
        HarnessSignIns.View view = start("TEST-seat-liar");

        signIns.completed(new HarnessSignInResult.Completed(view.id().toString(),
                encryption.encryptString(API_KEY_FILE, HarnessSignInResult.sealedAad(view.id().toString())),
                "TEST-claims-a-subscription", "TEST-claims-a-subscription"));

        HarnessSignIns.View after = signIns.get(view.id()).orElseThrow();
        assertEquals("FAILED", after.state());
        assertEquals(HarnessSignInResult.Failed.WRONG_MODE, after.reason());
        assertTrue(pool.list().stream().noneMatch(member -> member.label().equals("TEST-seat-liar")));
    }

    /** And bytes that are not a readable sign-in file at all are refused rather than stored blind. */
    @Test
    void bytesThatAreNotAReadableSignInFileAreRefused() {
        HarnessSignIns.View view = start("TEST-seat-garbage");

        signIns.completed(new HarnessSignInResult.Completed(view.id().toString(),
                encryption.encryptString("TEST-not-json-at-all", HarnessSignInResult.sealedAad(view.id().toString())),
                "TEST-chatgpt", "TEST-chatgpt"));

        assertEquals("FAILED", signIns.get(view.id()).orElseThrow().state());
        assertTrue(pool.list().stream().noneMatch(member -> member.label().equals("TEST-seat-garbage")));
    }

    /** One sign-in, one member. A redelivered completion must not create a second holder of one seat. */
    @Test
    void aRepeatedCompletionDoesNotCreateASecondMember() {
        HarnessSignIns.View view = start("TEST-seat-once");

        signIns.completed(completion(view.id(), SUBSCRIPTION_FILE));
        signIns.completed(completion(view.id(), SUBSCRIPTION_FILE));

        assertEquals(1, pool.list().stream().filter(member -> member.label().equals("TEST-seat-once")).count());
    }

    /** Two units racing for one seat is two codes on one screen and a coin toss over what gets stored. */
    @Test
    void onlyOneSignInPerHarnessRunsAtATime() {
        start("TEST-seat-first");

        HarnessSignIns.Started second = signIns.start("TEST-seat-second", HARNESS, "TEST-operator");

        assertNull(second.view());
        assertEquals("sign_in_already_running", second.refusal());
    }

    /** The name is refused BEFORE a container starts, not after a person has signed in for nothing. */
    @Test
    void aNameAlreadyUsedByACredentialIsRefusedBeforeAnythingStarts() {
        pool.add("TEST-seat-taken", "openai", "https://api.example.test", "sk-TEST-not-real");

        HarnessSignIns.Started refused = signIns.start("TEST-seat-taken", HARNESS, "TEST-operator");

        assertNull(refused.view());
        assertEquals("harness_credential_label_taken", refused.refusal());
    }

    @Test
    void aHarnessThisDeploymentHasNoImageForCannotBeSignedIn() {
        HarnessSignIns.Started refused = signIns.start("TEST-seat-nowhere", "TEST-unknown-harness", "TEST-operator");

        assertNull(refused.view());
        assertEquals("harness_unconfigured", refused.refusal());
    }

    @Test
    void aCancelledSignInStopsAndSaysSo() {
        HarnessSignIns.View view = start("TEST-seat-cancel");

        assertTrue(signIns.cancel(view.id(), "the operator cancelled it"));

        HarnessSignIns.View after = signIns.get(view.id()).orElseThrow();
        assertEquals("FAILED", after.state());
        assertEquals(HarnessSignInResult.Failed.CANCELLED, after.reason());
        // And the harness is free again, or an abandoned sign-in would lock the seat out for ever.
        assertTrue(signIns.inProgress(HARNESS).isEmpty());
    }

    /** A completion for a sign-in that already failed must not resurrect it into a member. */
    @Test
    void aCompletionAfterACancelCreatesNoMember() {
        HarnessSignIns.View view = start("TEST-seat-late");
        assertTrue(signIns.cancel(view.id(), "the operator cancelled it"));

        signIns.completed(completion(view.id(), SUBSCRIPTION_FILE));

        assertEquals("FAILED", signIns.get(view.id()).orElseThrow().state());
        assertTrue(pool.list().stream().noneMatch(member -> member.label().equals("TEST-seat-late")));
    }
}
