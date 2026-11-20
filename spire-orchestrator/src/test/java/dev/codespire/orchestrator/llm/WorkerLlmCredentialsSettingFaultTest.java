package dev.codespire.orchestrator.llm;

import dev.codespire.orchestrator.settings.AppSettingRepository;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * A database fault reading the default review model throws, so the review is redelivered; it is never refused
 * {@code NO_DEFAULT_MODEL}, which is final. Hand-built, so the fault is the setting read and nothing else.
 *
 * <p>The settings fake answers a fault the way each real read does ({@code AppSettingRepositoryRequireTest} proves
 * that): {@code require} throws, {@code get} reads empty. A resolution that used {@code get} would refuse instead of
 * throwing, and fail here.
 */
class WorkerLlmCredentialsSettingFaultTest {

    @Test
    void aSettingReadFaultThrowsAndIsNotARefusal() {
        WorkerLlmCredentials credentials = new WorkerLlmCredentials();
        credentials.registry = defaultProviderPresent();
        credentials.defaultModel = new LlmDefaultModel();
        credentials.defaultModel.settings = databaseDown();
        credentials.models = new LlmModelRegistry() {
            @Override
            public boolean isPriceable(String model) {
                throw new AssertionError("pricing is never asked when the model could not be read");
            }
        };

        assertThrows(AppSettingRepository.SettingReadFailed.class, () -> credentials.resolveDefault("TEST-ws"));
    }

    /** The registry answers before the setting is read: a default provider exists. */
    private static LlmProviderRegistry defaultProviderPresent() {
        return new LlmProviderRegistry() {
            @Override
            public Optional<LlmProviderConfig> resolveDefault() {
                return Optional.of(new LlmProviderConfig(UUID.randomUUID(), "TEST-provider", "openai",
                        "https://TEST-llm.example.invalid", "TEST-key", 0.2, null, true, true));
            }
        };
    }

    /** Every read fails as a database that is down does; every method a resolution can reach is overridden. */
    private static AppSettingRepository databaseDown() {
        return new AppSettingRepository() {
            @Override
            public Optional<String> require(String key) {
                throw new SettingReadFailed(key, new SQLException("TEST-unreachable"));
            }

            @Override
            public Optional<String> get(String key) {
                return Optional.empty();
            }
        };
    }
}
