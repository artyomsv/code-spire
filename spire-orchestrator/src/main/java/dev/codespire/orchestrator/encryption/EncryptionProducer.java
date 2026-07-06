package dev.codespire.orchestrator.encryption;

import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Wires the framework-free {@link EncryptionService} (spire-encryption) into CDI for the
 * orchestrator. Fail-fast: a missing {@code spire.encryption.keyset} is a startup
 * error (ADR-009 / ADR-015).
 */
@ApplicationScoped
public class EncryptionProducer {

    @Produces
    @Singleton
    public EncryptionService cryptoService(
            @ConfigProperty(name = "spire.encryption.keyset") Optional<String> keyset) {
        return EncryptionService.fromConfig(keyset);
    }
}
