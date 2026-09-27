package dev.codespire.orchestrator.factory;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Deleting a harness key or seat (operator feedback, 2026-09-27): switching off kept the key or the
 * person's sign-in file stored for ever. Placeholder values throughout.
 */
@QuarkusTest
class HarnessCredentialDeleteTest {

    @Inject HarnessCredentialPool pool;
    @Inject FactoryRunProjection runs;
    @Inject HarnessSignIns signIns;
    @Inject dev.codespire.encryption.EncryptionService encryption;
    @Inject DataSource dataSource;

    private static String label() {
        return "TEST-delete-" + UUID.randomUUID();
    }

    private UUID switchedOffKey(String label) {
        UUID id = pool.add(label, "openai", "https://api.openai.com", "TEST-key-to-delete").id();
        pool.remove(id);
        return id;
    }

    private long rows(String sql, Object id) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    private boolean listed(UUID id) {
        return pool.list().stream().anyMatch(member -> member.id().equals(id));
    }

    /** A member no run used is gone, and its name is free again. */
    @Test
    void aMemberNoRunUsedIsDeletedOutright() throws SQLException {
        String label = label();
        UUID id = switchedOffKey(label);

        assertEquals(HarnessCredentialPool.Deletion.DELETED, pool.delete(id));

        assertEquals(0, rows("SELECT count(*) FROM harness_credential WHERE id = ?", id));
        UUID again = pool.add(label, "openai", "https://api.openai.com", "TEST-key-again").id();
        pool.remove(again);
    }

    /**
     * A member a run used keeps the row the run points at, but loses its secret, leaves the screen and can
     * never be switched on again.
     */
    @Test
    void aMemberARunUsedIsErasedAndKeptOnlyForTheRun() throws SQLException {
        String label = label();
        UUID id = switchedOffKey(label);
        String runId = "run::github:TEST-acme/app:subject-" + UUID.randomUUID() + ":1";
        assertTrue(runs.queued(new FactoryRunProjection.QueuedRun(runId, "codex", "TEST-model", "main",
                "abc1234", "spire/TEST-delete", "TEST-bot", id), null, null));

        assertEquals(HarnessCredentialPool.Deletion.ERASED, pool.delete(id));

        assertEquals(1, rows("SELECT count(*) FROM harness_credential WHERE id = ? AND api_key IS NULL AND erased_at IS NOT NULL", id),
                "the secret is gone; the row stays for the run's attribution");
        assertFalse(listed(id), "an erased member is not on the pool screen");
        assertFalse(pool.enable(id), "an erased member has nothing to switch on");
        HarnessSignIns.Started signIn = signIns.start(label, "codex", "TEST-operator");
        assertNull(signIn.refusal(), "an erased member's name is free for a new sign-in");
        signIns.cancel(signIn.view().id(), "TEST: only the name was checked");
        UUID again = pool.add(label, "openai", "https://api.openai.com", "TEST-key-again").id();
        pool.remove(again);
    }

    /**
     * A dispatch picks a member and writes its run a moment later; a member picked lately may be about to
     * be named by a run not written yet, so it is erased rather than deleted (review of PR #179).
     */
    @Test
    void aMemberPickedLatelyIsErasedRatherThanDeleted() throws SQLException {
        UUID id = switchedOffKey(label());
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE harness_credential SET last_used_at = now() WHERE id = ?")) {
            ps.setObject(1, id);
            ps.executeUpdate();
        }

        assertEquals(HarnessCredentialPool.Deletion.ERASED, pool.delete(id));
        assertEquals(1, rows("SELECT count(*) FROM harness_credential WHERE id = ?", id), "the row a pending run needs stays");
    }

    /**
     * A key switched off and erased while a pick waits for it is not handed out, and not mistaken for a
     * corrupt key and marked refused (review of PR #179). Every other usable key is held and switched off
     * in the same transaction, so whichever the pick chose, it has to wait and re-check.
     */
    @Test
    void aKeyErasedWhileAPickWaitsIsNotReturnedNorMarkedRefused() throws Exception {
        UUID id = pool.add(label(), "openai", "https://api.openai.com", "TEST-key-erased-in-flight").id();
        java.util.List<UUID> others = new java.util.ArrayList<>();
        try (Connection other = dataSource.getConnection()) {
            other.setAutoCommit(false);
            try (PreparedStatement ps = other.prepareStatement("""
                    UPDATE harness_credential SET enabled = FALSE
                     WHERE enabled AND auth_mode = 'API_KEY' AND rejected_at IS NULL AND id <> ?
                    RETURNING id
                    """)) {
                ps.setObject(1, id);
                try (ResultSet rs = ps.executeQuery()) { while (rs.next()) others.add(rs.getObject(1, UUID.class)); }
            }
            try (PreparedStatement ps = other.prepareStatement(
                    "UPDATE harness_credential SET enabled = FALSE, api_key = NULL, erased_at = now() WHERE id = ?")) {
                ps.setObject(1, id);
                ps.executeUpdate();
            }
            java.util.concurrent.CompletableFuture<HarnessCredentialPool.Selection> picking =
                    java.util.concurrent.CompletableFuture.supplyAsync(pool::select);

            assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> picking.get(1, java.util.concurrent.TimeUnit.SECONDS), "the pick waits for the held rows");
            other.commit();
            HarnessCredentialPool.Selection selection = picking.get(30, java.util.concurrent.TimeUnit.SECONDS);

            assertFalse(selection instanceof HarnessCredentialPool.Selection.Chosen, "every candidate was switched off meanwhile");
            assertEquals(0, rows("SELECT count(*) FROM harness_credential WHERE id = ? AND rejected_at IS NOT NULL", id),
                    "an erased key is not a refused one");
        } finally {
            try (Connection c = dataSource.getConnection();
                 PreparedStatement ps = c.prepareStatement("UPDATE harness_credential SET enabled = TRUE WHERE id = ANY (?)")) {
                ps.setArray(1, c.createArrayOf("uuid", others.toArray()));
                ps.executeUpdate();
            }
        }
    }

    /** A member still switched on could be picked by a build at this moment, so it must be switched off first. */
    @Test
    void aMemberStillSwitchedOnIsRefused() {
        UUID id = pool.add(label(), "openai", "https://api.openai.com", "TEST-key-on").id();
        try {
            assertEquals(HarnessCredentialPool.Deletion.STILL_ON, pool.delete(id));
            assertTrue(listed(id));
        } finally {
            pool.remove(id);
        }
    }

    /** A seat's sign-in record stays as history and forgets the seat. */
    @Test
    void aSeatsSignInRecordForgetsTheDeletedSeat() throws SQLException {
        String label = "TEST-delete-seat-" + UUID.randomUUID();
        HarnessSignIns.Started started = signIns.start(label, "codex", "TEST-operator");
        assertNull(started.refusal(), started.refusal());
        UUID signIn = started.view().id();
        String file = "{\"auth_mode\":\"TEST-chatgpt\",\"tokens\":{\"account_id\":\"TEST-account-" + UUID.randomUUID() + "\"}}";
        signIns.completed(new dev.codespire.contract.event.HarnessSignInResult.Completed(signIn.toString(),
                encryption.encryptString(file, dev.codespire.contract.event.HarnessSignInResult.sealedAad(signIn.toString())),
                "TEST-chatgpt", "TEST-chatgpt"));
        UUID seat = signIns.get(signIn).orElseThrow().credentialId();
        pool.remove(seat);

        assertEquals(HarnessCredentialPool.Deletion.DELETED, pool.delete(seat));

        assertNull(signIns.get(signIn).orElseThrow().credentialId());
    }

    @Test
    @TestSecurity(user = "op", roles = "spire-admin")
    void theScreenIsToldToSwitchItOffFirst() {
        UUID id = pool.add(label(), "openai", "https://api.openai.com", "TEST-key-rest").id();
        try {
            given().when().post("/api/harness-credentials/" + id + "/delete")
                    .then().statusCode(409).body(org.hamcrest.Matchers.containsString("harness_credential_still_on"));
            pool.remove(id);
            given().when().post("/api/harness-credentials/" + id + "/delete")
                    .then().statusCode(200).body("outcome", org.hamcrest.Matchers.is("DELETED"));
        } finally {
            pool.remove(id);
        }
    }
}
