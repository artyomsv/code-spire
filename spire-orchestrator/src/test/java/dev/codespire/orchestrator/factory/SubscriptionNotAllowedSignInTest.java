package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.HarnessSignInResult;
import dev.codespire.encryption.EncryptionService;
import io.quarkus.arc.ClientProxy;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The sign-in and pool doors of "a subscription for Claude Code is refused at every door" (harness reviews 3b, Review
 * Focus 2): starting a sign-in, completing one opened before the refusal existed, and storing a seat. A Quarkus test
 * because each writes in the database. Each Codex twin is the regression guard: it does not refuse for this reason.
 */
@QuarkusTest
class SubscriptionNotAllowedSignInTest {

    private static final String CLAUDE = "claude_code";
    private static final String CODEX = "codex";
    private static final String NOT_ALLOWED = "subscription_not_allowed";

    /** Only what this class made. */
    private static final String OWNED = "TEST-seat-3b-%";

    @Inject HarnessSignIns signIns;
    @Inject HarnessCredentialPool pool;
    @Inject EncryptionService encryption;
    @Inject DataSource dataSource;

    private final List<String> reached = new ArrayList<>();

    @AfterEach
    void clean() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM harness_sign_in WHERE label LIKE '" + OWNED + "'");
            s.executeUpdate("DELETE FROM harness_credential WHERE label LIKE '" + OWNED + "'"
                    + " AND id NOT IN (SELECT harness_credential_id FROM factory_run WHERE harness_credential_id IS NOT NULL)");
        }
    }

    // --- the sign-in -----------------------------------------------------------------------------

    private int signInRows(String label) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM harness_sign_in WHERE label=?")) {
            ps.setString(1, label);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    @Test
    @TestSecurity(user = "TEST-operator", roles = "spire-admin")
    void aClaudeSignInCannotStart() throws SQLException {
        given().contentType("application/json")
                .body("{\"label\":\"TEST-seat-3b-start\",\"harness\":\"" + CLAUDE + "\"}")
                .when().post("/api/harness-credentials/sign-in")
                .then().statusCode(409).body("reason", equalTo(NOT_ALLOWED));

        assertEquals(0, signInRows("TEST-seat-3b-start"), "no sign-in row is opened");
    }

    @Test
    void aCodexSignInStartIsNotRefusedForIt() {
        HarnessSignIns.Started started = signIns.start("TEST-seat-3b-codex-start", CODEX, "TEST-operator");

        assertNotEquals(NOT_ALLOWED, started.refusal());
    }

    /** A pending row as one opened before this change could be: inserted directly, since start refuses it now. */
    private UUID olderPendingRow(String label, String harness) throws SQLException {
        UUID id = UUID.randomUUID();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "INSERT INTO harness_sign_in (id, label, harness, state, started_by, created_at) VALUES (?, ?, ?, 'PENDING', 'TEST-operator', now())")) {
            ps.setObject(1, id); ps.setString(2, label); ps.setString(3, harness);
            ps.executeUpdate();
        }
        return id;
    }

    private HarnessSignInResult.Completed completion(UUID id) {
        String body = "{\"auth_mode\":\"TEST-chatgpt\",\"tokens\":{\"access\":\"TEST-PLACEHOLDER\",\"account_id\":\"TEST-account-"
                + UUID.randomUUID() + "\"}}";
        return new HarnessSignInResult.Completed(id.toString(),
                encryption.encryptString(body, HarnessSignInResult.sealedAad(id.toString())), "TEST-chatgpt", "TEST-chatgpt");
    }

    /**
     * Faked to SUCCEED, so only the completion's own guard can refuse: a refusal from addSubscription would be a
     * different door.
     */
    private HarnessCredentialPool succeedingPool() {
        return new HarnessCredentialPool() {
            @Override public UUID addSubscription(Connection c, String label, String type, String body) {
                reached.add("addSubscription");
                return UUID.randomUUID();
            }
            @Override public Optional<UUID> seatFor(Connection c, String type, String account) {
                reached.add("seatFor");
                return Optional.empty();
            }
            @Override public boolean hasLabel(Connection c, String label) {
                reached.add("hasLabel");
                return false;
            }
            @Override public void replaceSubscription(Connection c, UUID seat, String body) {
                reached.add("replaceSubscription");
            }
        };
    }

    @Test
    void aSignInCompletionForAnOlderPendingRowIsRefused() throws SQLException {
        UUID id = olderPendingRow("TEST-seat-3b-older", CLAUDE);
        HarnessSignIns target = ClientProxy.unwrap(signIns);
        HarnessCredentialPool original = target.pool;
        target.pool = succeedingPool();
        try {
            signIns.completed(completion(id));
        } finally {
            target.pool = original;
        }

        HarnessSignIns.View view = signIns.get(id).orElseThrow();
        assertEquals("FAILED", view.state());
        assertEquals(NOT_ALLOWED, view.reason());
        assertEquals(List.of(), reached, "no seat is looked up, replaced or stored");
    }

    @Test
    void aCodexCompletionForAnOlderPendingRowIsNotRefusedForIt() throws SQLException {
        UUID id = olderPendingRow("TEST-seat-3b-older-codex", CODEX);

        signIns.completed(completion(id));

        assertEquals("COMPLETE", signIns.get(id).orElseThrow().state());
    }

    // --- the pool ---------------------------------------------------------------------------------

    private int credentialRows(String label) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM harness_credential WHERE label=?")) {
            ps.setString(1, label);
            try (ResultSet rs = ps.executeQuery()) { rs.next(); return rs.getInt(1); }
        }
    }

    private static String seatFile() {
        return "{\"auth_mode\":\"TEST-chatgpt\",\"tokens\":{\"access\":\"TEST-PLACEHOLDER\",\"account_id\":\"TEST-account-"
                + UUID.randomUUID() + "\"}}";
    }

    @Test
    void addSubscriptionRefusesAClaudeSeat() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> pool.addSubscription(c, "TEST-seat-3b-direct", CLAUDE, seatFile()));
            assertEquals(NOT_ALLOWED, refused.getMessage());
        }

        assertEquals(0, credentialRows("TEST-seat-3b-direct"), "no row is stored");
    }

    @Test
    void addSubscriptionStoresACodexSeat() throws SQLException {
        try (Connection c = dataSource.getConnection()) {
            pool.addSubscription(c, "TEST-seat-3b-direct-codex", CODEX, seatFile());
        }

        assertEquals(1, credentialRows("TEST-seat-3b-direct-codex"));
    }
}
