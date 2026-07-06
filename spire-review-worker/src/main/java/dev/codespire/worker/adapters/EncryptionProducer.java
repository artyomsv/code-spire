package dev.codespire.worker.adapters;

import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Wires the framework-free {@link EncryptionService} (spire-encryption) into CDI for the
 * worker, which — since ADR-015 — holds the master keyset to decrypt the
 * per-command SCM credential. Fail-fast: a missing {@code spire.encryption.keyset}
 * is a startup error.
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
