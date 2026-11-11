package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.HarnessSignInResult;
import dev.codespire.contract.review.TokenType;
import dev.codespire.contract.work.PayWith;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.orchestrator.caps.SpendGate;
import dev.codespire.orchestrator.llm.LlmModelPricer;
import dev.codespire.orchestrator.llm.LlmModelRegistry;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.repository.RepositoryInput;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.repository.ReviewHarness;
import dev.codespire.orchestrator.work.WorkTestDoors;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A subscription for Claude Code is refused at every door it can arrive by (harness reviews 3b, Review Focus 2).
 *
 * <p>Claude Code never pays with a Claude subscription: Anthropic's terms forbid a third-party product to route
 * requests through one or to hold claude.ai tokens. Nothing here signs in to, stores or runs one; each test hands one
 * door a subscription and asserts that door alone refuses it, with the collaborators behind the door failing the test
 * if they are reached. Each door's Codex twin is the regression guard: it does not refuse for this reason.
 *
 * <p>A Quarkus test because the sign-in completion and {@code addSubscription} write in the database; the other doors
 * are built by hand with fakes, because this deployment's test profile has no Claude Code agent image.
 */
@QuarkusTest
class SubscriptionNotAllowedTest {

    private static final String CLAUDE = "claude_code";
    private static final String CODEX = "codex";
    private static final String NOT_ALLOWED = "subscription_not_allowed";

    /** Only what this class made. */
    private static final String OWNED = "TEST-seat-3b-%";

    @Inject RepositoryRegistry repositories;
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

    // --- the review setting ------------------------------------------------------------------

    private static RepositoryInput reviewingWith(String harness, String payWith) {
        return new RepositoryInput("github", "https://github.com", "TEST-ws", "TEST-3b-" + UUID.randomUUID(), true,
                null, null, true, null, new ReviewHarness(harness, "TEST-model", null, payWith));
    }

    @Test
    void aReviewSettingWithAClaudeSubscriptionIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> repositories.update(UUID.randomUUID(), 1, reviewingWith(CLAUDE, PayWith.SUBSCRIPTION)));

        assertTrue(refused.getMessage().contains(NOT_ALLOWED), refused.getMessage());
    }

    /** Past every check: the update then finds no such repository, which is the write, not a refusal. */
    @Test
    void aReviewSettingWithACodexSubscriptionIsNotRefusedForIt() {
        RuntimeException outcome = assertThrows(RuntimeException.class,
                () -> repositories.update(UUID.randomUUID(), 1, reviewingWith(CODEX, PayWith.SUBSCRIPTION)));

        assertInstanceOf(ProviderRegistry.AccountConflict.class, outcome, outcome.getMessage());
    }

    // --- build defaults ------------------------------------------------------------------------

    /** Both harnesses configured, so neither is refused as unconfigured before the guard under test. */
    static FactoryConfig bothHarnesses() {
        return new FactoryConfig() {
            @Override public Map<String, String> agentImage() { return Map.of(CODEX, "TEST-codex-image", CLAUDE, "TEST-claude-image"); }
            @Override public long wallClockSeconds() { return 1800; }
            @Override public long reviewWallClockSeconds() { return 900; }
            @Override public Fix fix() { throw new AssertionError("no door here reads the fix configuration"); }
        };
    }

    private BuildDefaults buildDefaults() {
        BuildDefaults defaults = new BuildDefaults();
        defaults.config = bothHarnesses();
        defaults.catalogues = new HarnessCatalogues() {
            @Override public Optional<String> refusal(String harness, String model, String effort) {
                reached.add("catalogue");
                return Optional.empty();
            }
        };
        defaults.pool = new HarnessCredentialPool() {
            @Override public boolean hasSubscription(String harness) {
                reached.add("seat");
                return false;
            }
        };
        defaults.models = new LlmModelRegistry() {
            @Override public List<dev.codespire.orchestrator.llm.LlmModelView> list() { throw new AssertionError("a subscription is not priced"); }
        };
        defaults.pricer = new LlmModelPricer() {
            @Override public List<TokenType> unpricedTypes(String model, String harness) { throw new AssertionError("a subscription is not priced"); }
        };
        return defaults;
    }

    private String buildDefaultsRefusal(String harness) {
        BuildDefaults.Input input = new BuildDefaults.Input(1, "main", harness, "TEST-model", null, PayWith.SUBSCRIPTION);
        return assertThrows(BuildDefaults.Refused.class,
                () -> buildDefaults().save(UUID.randomUUID(), input, "TEST-operator")).reason();
    }

    @Test
    void buildDefaultsWithAClaudeSubscriptionAreRefused() {
        assertEquals(NOT_ALLOWED, buildDefaultsRefusal(CLAUDE));
        assertEquals(List.of(), reached, "refused before the catalogue and the seat are asked");
    }

    @Test
    void buildDefaultsWithACodexSubscriptionAreNotRefusedForIt() {
        assertEquals("subscription_not_signed_in", buildDefaultsRefusal(CODEX));
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

    @Test
    void aSignInCompletionForAnOlderPendingRowIsRefused() throws SQLException {
        UUID id = olderPendingRow("TEST-seat-3b-older", CLAUDE);
        HarnessSignIns target = ClientProxy.unwrap(signIns);
        HarnessCredentialPool original = target.pool;
        // Faked to SUCCEED, so only the completion's own guard can refuse: a refusal from addSubscription would be
        // a different door.
        target.pool = new HarnessCredentialPool() {
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

    // --- funding a stored choice ---------------------------------------------------------------------

    private RunFunding funding() {
        RunFunding funding = new RunFunding();
        funding.models = new LlmModelRegistry() {
            @Override public boolean isDisabled(String model) {
                reached.add("models");
                return false;
            }
        };
        funding.catalogues = new HarnessCatalogues() {
            @Override public Admission admit(String harness, String model, String effort) {
                reached.add("admit");
                return new Admission(null, "TEST-image");
            }
        };
        funding.pricer = new LlmModelPricer() {
            @Override public List<TokenType> unpricedTypes(String model, String harness) {
                reached.add("pricer");
                return List.of();
            }
        };
        funding.spend = new SpendGate() {
            @Override public Decision decide() {
                reached.add("spend");
                return Decision.of(dev.codespire.orchestrator.caps.CapRefusal.allow());
            }
        };
        funding.pool = new HarnessCredentialPool() {
            @Override public Optional<PoolMember> selectSubscription(String harness) {
                reached.add("seat");
                return Optional.empty();
            }
            @Override public Selection select(Optional<String> keyKind) {
                throw new AssertionError("a subscription choice never asks for a key");
            }
        };
        return funding;
    }

    /** A hand-made dispatch, or a row stored before this change: the choice reaches fund as it is. */
    @Test
    void aStoredClaudeSubscriptionIsRefusedFirst() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> funding().fund(new RunFunding.Choice(CLAUDE, "TEST-model", null, true)));

        assertEquals(NOT_ALLOWED, refused.getMessage());
        assertEquals(List.of(), reached, "refused before the image, the price list, the spend gate and the seat");
    }

    @Test
    void aStoredCodexSubscriptionIsNotRefusedForIt() {
        IllegalStateException refused = assertThrows(IllegalStateException.class,
                () -> funding().fund(new RunFunding.Choice(CODEX, "TEST-model", null, true)));

        assertEquals("subscription_unavailable", refused.getMessage());
        assertEquals(List.of("admit", "spend", "seat"), reached);
    }

    // --- what the screens are offered --------------------------------------------------------------------

    @Test
    void optionsNameTheAllowedHarnesses() {
        RepositoryBuildResource resource = new RepositoryBuildResource();
        resource.config = bothHarnesses();
        resource.catalogues = new HarnessCatalogues() {
            @Override public Optional<Catalogue> get(String harness) { return Optional.empty(); }
        };

        RepositoryBuildResource.Options build = resource.options();
        assertEquals(List.of(CLAUDE, CODEX), build.harnesses(), "every configured harness is still listed");
        assertEquals(List.of(CODEX), build.subscriptionHarnesses());
        assertEquals(List.of(CODEX), build.buildHarnesses());

        dev.codespire.orchestrator.work.WorkPreparationResource.Options preparation =
                WorkTestDoors.preparationOptions(bothHarnesses());
        assertEquals(List.of(CODEX), preparation.subscriptionHarnesses());
        assertEquals(List.of(CODEX), preparation.buildHarnesses());
    }
}
