package dev.codespire.orchestrator.llm;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.llm.ModelParamProfile;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.orchestrator.settings.AppSettingRepository;
import dev.codespire.orchestrator.settings.ReviewDiffLimit;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR 456, security/M-1: the Review diff limit cannot be read, on a model whose catalog window is known. The pack
 * throws, as a failed read of the default provider does, so the review is retried; no credential is encrypted, so no
 * review is sent with the operator's cap lifted. Hand-built with fakes: no container, no database.
 */
class WorkerLlmCredentialsLimitFaultTest {

    @Test
    void aFailedLimitReadEncryptsNoCredential() {
        List<String> encrypted = new ArrayList<>();
        WorkerLlmCredentials credentials = new WorkerLlmCredentials();
        credentials.registry = new LlmProviderRegistry() {
            @Override
            public Optional<ResolvedDefault> resolveDefaultWithModel() {
                return Optional.of(new ResolvedDefault(new LlmProviderConfig(UUID.randomUUID(), "TEST-provider",
                        "openai", "https://llm.example.invalid", "TEST-key", 0.2, null, true, true), "TEST-model"));
            }
        };
        credentials.models = new LlmModelRegistry() {
            @Override
            public boolean isPriceable(String model) {
                return true;
            }

            @Override
            public Optional<ModelParamProfile> profileForName(String name) {
                return Optional.of(ModelParamProfile.legacyDefault());
            }
        };
        credentials.encryption = new EncryptionService(EncryptionService.generateKeysetBase64()) {
            @Override
            public String encryptString(String plaintext, String aad) {
                encrypted.add(plaintext);
                return "TEST-ciphertext";
            }
        };
        credentials.mapper = new ObjectMapper();
        credentials.inputLimits = knownWindowWithUnreadableLimit();

        assertThrows(AppSettingRepository.ReadFailed.class, () -> credentials.resolveDefault("TEST-ws"));
        assertTrue(encrypted.isEmpty(), "no credential is packed without its cap");
    }

    private static ReviewInputLimits knownWindowWithUnreadableLimit() {
        ReviewInputLimits limits = new ReviewInputLimits() {
            @Override
            Optional<Long> contextWindow(String model) {
                return Optional.of(1_000_000L);
            }
        };
        limits.diffLimit = new ReviewDiffLimit() {
            @Override
            public Optional<Integer> current() {
                throw new AppSettingRepository.ReadFailed(ReviewDiffLimit.KEY, new SQLException("TEST-unreachable"));
            }
        };
        return limits;
    }
}
