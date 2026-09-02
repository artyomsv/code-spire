package dev.codespire.runworker;

import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * The worker's KEK, from the same {@code SPIRE_ENCRYPTION_KEYSET} the orchestrator packs with
 * (ADR-015). Fail-fast when absent: a worker without it cannot read a single command.
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
