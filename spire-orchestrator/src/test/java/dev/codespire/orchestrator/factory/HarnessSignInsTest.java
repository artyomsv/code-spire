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
    private static final String SUBSCRIPTION_FILE =
            "{\"auth_mode\":\"TEST-chatgpt\",\"tokens\":{\"access\":\"TEST-not-a-real-token\"}}";

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
