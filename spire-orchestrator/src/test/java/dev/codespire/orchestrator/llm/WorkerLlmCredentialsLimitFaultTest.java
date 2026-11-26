package dev.codespire.orchestrator.llm;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.llm.ModelParamProfile;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.orchestrator.settings.AppSettingRepository;
import dev.codespire.orchestrator.settings.ReviewDiffLimit;
import dev.codespire.orchestrator.settings.ReviewDiffLimitFixture;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR 456, security/M-1 and code-quality/M-4: the Review diff limit as the credential pack reads it, on a model whose
 * catalog window is known. A failed read packs the documented conservative bound, a 24,000-token diff cap, as an
 * invalid row does, so the review goes on and the operator's cap is never lifted; only no row packs none. The real
 * {@link ReviewDiffLimit} over a repository fake: no container, no database.
 */
class WorkerLlmCredentialsLimitFaultTest {

    private static final String WORKSPACE = "TEST-ws";

    private final List<String> encrypted = new ArrayList<>();
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void aFailedLimitReadPacksTheLowestLimit() throws Exception {
        DefaultLlm llm = credentialsWith(unreachableSetting()).resolveDefault(WORKSPACE);

        assertTrue(llm.isSpendable(), "a failed limit read never blocks a review: " + llm.detail());
        assertEquals(ReviewDiffLimit.MIN_TOKENS, packedDiffTokenCap().asInt());
    }

    @Test
    void anInvalidStoredLimitPacksTheLowestLimit() throws Exception {
        credentialsWith(holding("TEST-abc")).resolveDefault(WORKSPACE);

        assertEquals(ReviewDiffLimit.MIN_TOKENS, packedDiffTokenCap().asInt());
    }

    @Test
    void noStoredLimitPacksNone() throws Exception {
        credentialsWith(holding(null)).resolveDefault(WORKSPACE);

        JsonNode cap = packedDiffTokenCap();

        assertTrue(cap.isNull() || cap.isMissingNode(), "unset is the operator's own choice: " + cap);
    }

    /** The cap as the JSON the worker decrypts carries it. */
    private JsonNode packedDiffTokenCap() throws Exception {
        assertEquals(1, encrypted.size(), "exactly one credential is packed");
        return mapper.readTree(encrypted.getFirst()).path("diffTokenCap");
    }

    private WorkerLlmCredentials credentialsWith(AppSettingRepository settings) {
        WorkerLlmCredentials credentials = new WorkerLlmCredentials();
        credentials.registry = oneDefaultProvider();
        credentials.models = priceableModels();
        credentials.encryption = recordingEncryption();
        credentials.mapper = mapper;
        credentials.inputLimits = knownWindowOver(settings);
        return credentials;
    }

    private static LlmProviderRegistry oneDefaultProvider() {
        return new LlmProviderRegistry() {
            @Override
            public Optional<ResolvedDefault> resolveDefaultWithModel() {
                return Optional.of(new ResolvedDefault(new LlmProviderConfig(UUID.randomUUID(), "TEST-provider",
                        "openai", "https://llm.example.invalid", "TEST-key", 0.2, null, true, true), "TEST-model"));
            }
        };
    }

    private static LlmModelRegistry priceableModels() {
        return new LlmModelRegistry() {
            @Override
            public boolean isPriceable(String model) {
                return true;
            }

            @Override
            public Optional<ModelParamProfile> profileForName(String name) {
                return Optional.of(ModelParamProfile.legacyDefault());
            }
        };
    }

    private EncryptionService recordingEncryption() {
        return new EncryptionService(EncryptionService.generateKeysetBase64()) {
            @Override
            public String encryptString(String plaintext, String aad) {
                encrypted.add(plaintext);
                return "TEST-ciphertext";
            }
        };
    }

    private static ReviewInputLimits knownWindowOver(AppSettingRepository settings) {
        ReviewInputLimits limits = new ReviewInputLimits() {
            @Override
            Optional<Long> contextWindow(String model) {
                return Optional.of(1_000_000L);
            }
        };
        limits.diffLimit = ReviewDiffLimitFixture.over(settings);
        return limits;
    }

    /** The strict read throws, as {@link AppSettingRepository#getStrictly} does when the database is down. */
    private static AppSettingRepository unreachableSetting() {
        return new AppSettingRepository() {
            @Override
            public Optional<String> getStrictly(String key) {
                throw new ReadFailed(key, new SQLException("TEST-unreachable"));
            }
        };
    }

    private static AppSettingRepository holding(String value) {
        return new AppSettingRepository() {
            @Override
            public Optional<String> getStrictly(String key) {
                return Optional.ofNullable(value);
            }
        };
    }
}
