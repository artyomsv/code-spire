package dev.codespire.orchestrator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.llm.LlmCredential;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.orchestrator.attention.AttentionBroadcaster;
import dev.codespire.orchestrator.settings.AppSettingRepository;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The default provider and model a review spends come from one committed state. The registry here switches the
 * default (another vendor's provider and model, committed) the moment the default is read; the credential must still
 * carry the provider and the model read together, never one default's provider with the other's model.
 */
@QuarkusTest
class WorkerLlmCredentialsDefaultPairTest {

    private static final String WORKSPACE = "TEST-pair-ws";

    @Inject LlmProviderRegistry providers;
    @Inject LlmDefaultModel defaultModels;
    @Inject LlmModelRegistry models;
    @Inject EncryptionService encryption;
    @Inject ObjectMapper mapper;
    @Inject AttentionBroadcaster attention;
    @Inject AppSettingRepository appSettings;
    @Inject ReviewInputLimits inputLimits;
    @Inject DataSource dataSource;

    private String openaiModel;
    private String anthropicModel;
    private UUID anthropic;

    @BeforeEach
    void anOpenaiDefault() throws SQLException {
        execute("DELETE FROM app_setting WHERE key=?", LlmDefaultModel.KEY);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        openaiModel = catalogued("openai", "TEST-pair-openai-" + suffix);
        anthropicModel = catalogued("anthropic", "TEST-pair-anthropic-" + suffix);
        UUID openai = provider("TEST-pair-openai", "openai");
        anthropic = provider("TEST-pair-anthropic", "anthropic");
        defaultModels.setDefaultProvider(openai, openaiModel);
    }

    @AfterEach
    void removeFixture() throws SQLException {
        execute("DELETE FROM llm_provider WHERE name LIKE ?", "TEST-pair-%");
        execute("DELETE FROM app_setting WHERE key=?", LlmDefaultModel.KEY);
        execute("DELETE FROM llm_model WHERE name LIKE ?", "TEST-pair-%");
    }

    @Test
    void aDefaultChangedRightAfterTheReadIsNotMixedIn() throws Exception {
        WorkerLlmCredentials credentials = new WorkerLlmCredentials();
        credentials.registry = switchingAfterEachRead();
        credentials.models = models;
        credentials.encryption = encryption;
        credentials.mapper = mapper;
        credentials.inputLimits = inputLimits; // pack reads the input limits: an unset field would NPE there

        DefaultLlm llm = credentials.resolveDefault(WORKSPACE);

        assertTrue(llm.isSpendable(), llm.detail());
        assertEquals(openaiModel, llm.model(), "the model read with the provider");
        JsonNode packed = mapper.readTree(encryption.decryptString(llm.packed(), LlmCredential.aad(WORKSPACE)));
        assertEquals("openai", packed.get("type").asText(), "the provider read with the model");
        assertEquals(openaiModel, packed.get("model").asText());
    }

    /** The real registry, which commits a switch to the anthropic default after every read of the default. */
    private LlmProviderRegistry switchingAfterEachRead() {
        LlmProviderRegistry hooked = new LlmProviderRegistry() {
            @Override
            public Optional<LlmProviderConfig> resolveDefault() {
                Optional<LlmProviderConfig> read = super.resolveDefault();
                switchToAnthropic();
                return read;
            }

            @Override
            public Optional<ResolvedDefault> resolveDefaultWithModel() {
                Optional<ResolvedDefault> read = super.resolveDefaultWithModel();
                switchToAnthropic();
                return read;
            }
        };
        hooked.dataSource = dataSource;
        hooked.encryption = encryption;
        hooked.attention = attention;
        hooked.appSettings = appSettings;
        return hooked;
    }

    private void switchToAnthropic() {
        try {
            execute("UPDATE llm_provider SET is_default = FALSE WHERE is_default");
            execute("UPDATE llm_provider SET is_default = TRUE WHERE id=?", anthropic);
            execute("UPDATE app_setting SET value=? WHERE key=?", anthropicModel, LlmDefaultModel.KEY);
        } catch (SQLException e) {
            throw new IllegalStateException("could not switch the TEST default", e);
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

    private void execute(String sql, Object... parameters) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                ps.setObject(i + 1, parameters[i]);
            }
            ps.executeUpdate();
        }
    }
}
