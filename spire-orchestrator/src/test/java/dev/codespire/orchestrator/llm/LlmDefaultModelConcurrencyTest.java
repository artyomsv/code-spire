package dev.codespire.orchestrator.llm;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Writers of the default pair are serialised on the real database. A model-only edit that starts while another
 * transaction is switching the default to another vendor waits for it, then validates against the new default and is
 * refused, so the stored pair stays whole.
 */
@QuarkusTest
class LlmDefaultModelConcurrencyTest {

    @Inject LlmDefaultModel defaultModels;
    @Inject LlmProviderRegistry providers;
    @Inject LlmModelRegistry models;
    @Inject DataSource dataSource;

    private String openaiModel;
    private String otherOpenaiModel;
    private String anthropicModel;
    private UUID anthropic;

    @BeforeEach
    void anOpenaiDefault() throws SQLException {
        execute("DELETE FROM app_setting WHERE key=?", LlmDefaultModel.KEY);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        openaiModel = catalogued("openai", "TEST-ldc-openai-" + suffix);
        otherOpenaiModel = catalogued("openai", "TEST-ldc-openai-other-" + suffix);
        anthropicModel = catalogued("anthropic", "TEST-ldc-anthropic-" + suffix);
        UUID openai = provider("TEST-ldc-openai", "openai");
        anthropic = provider("TEST-ldc-anthropic", "anthropic");
        defaultModels.setDefaultProvider(openai, openaiModel);
    }

    @AfterEach
    void removeFixture() throws SQLException {
        execute("DELETE FROM llm_provider WHERE name LIKE ?", "TEST-ldc-%");
        execute("DELETE FROM app_setting WHERE key=?", LlmDefaultModel.KEY);
        execute("DELETE FROM llm_model WHERE name LIKE ?", "TEST-ldc-%");
    }

    @Test
    void aModelOnlyEditWaitsForAConcurrentDefaultSwitch() throws Exception {
        CompletableFuture<Throwable> edit;
        try (Connection switching = dataSource.getConnection()) {
            switching.setAutoCommit(false);
            switchToAnthropicUncommitted(switching);

            edit = CompletableFuture.supplyAsync(this::setOtherOpenaiModel);
            awaitABlockedLock();
            switching.commit();
        }

        assertInstanceOf(LlmDefaultModel.ChoiceRefused.class, edit.get(30, TimeUnit.SECONDS),
                "the edit validates against the default committed while it waited");
        assertTrue(providers.get(anthropic).orElseThrow().isDefault());
        assertEquals(Optional.of(anthropicModel), setting(), "the pair stays whole: anthropic with its model");
    }

    /** As setDefaultProvider does it: the pair's lock, then the provider and the model, not yet committed. */
    private void switchToAnthropicUncommitted(Connection c) throws SQLException {
        LlmDefaultModel.lockDefaultPair(c);
        run(c, "UPDATE llm_provider SET is_default = FALSE WHERE is_default");
        run(c, "UPDATE llm_provider SET is_default = TRUE WHERE id = '" + anthropic + "'");
        run(c, "UPDATE app_setting SET value = '" + anthropicModel + "' WHERE key = '" + LlmDefaultModel.KEY + "'");
    }

    private Throwable setOtherOpenaiModel() {
        try {
            defaultModels.set(otherOpenaiModel);
            return null;
        } catch (RuntimeException outcome) {
            return outcome;
        }
    }

    /** Until the edit is seen waiting on a lock (bounded; without serialisation it never waits on ours). */
    private void awaitABlockedLock() throws SQLException, InterruptedException {
        Instant deadline = Instant.now().plus(Duration.ofSeconds(10));
        while (Instant.now().isBefore(deadline)) {
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                    "SELECT count(*) FROM pg_locks WHERE NOT granted"); ResultSet rs = ps.executeQuery()) {
                rs.next();
                if (rs.getInt(1) > 0) {
                    return;
                }
            }
            TimeUnit.MILLISECONDS.sleep(50);
        }
    }

    private Optional<String> setting() throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT value FROM app_setting WHERE key=?")) {
            ps.setString(1, LlmDefaultModel.KEY);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(rs.getString(1)) : Optional.empty();
            }
        }
    }

    private String catalogued(String type, String name) {
        models.create(new LlmModelInput(type, name, name, "UNMETERED", Map.of(),
                null, null, null, null, true, List.of()));
        return name;
    }

    private UUID provider(String name, String type) {
        return UUID.fromString(providers.create(new LlmProviderInput(name, type,
                "https://llm.example.invalid", "TEST-key", null, null, true, false, null)).id());
    }

    private static void run(Connection c, String sql) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        }
    }

    private void execute(String sql, Object... parameters) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                ps.setObject(i + 1, parameters[i]);
            }
            ps.executeUpdate();
        }
    }
}
