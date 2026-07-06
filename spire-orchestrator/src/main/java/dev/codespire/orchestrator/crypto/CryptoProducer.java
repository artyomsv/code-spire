package dev.codespire.orchestrator.crypto;

import dev.codespire.crypto.CryptoService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * Wires the framework-free {@link CryptoService} (spire-crypto) into CDI for the
 * orchestrator. Fail-fast: a missing {@code spire.encryption.keyset} is a startup
 * error (ADR-009 / ADR-015).
 */
@ApplicationScoped
public class CryptoProducer {

    @Produces
    @Singleton
    public CryptoService cryptoService(
            @ConfigProperty(name = "spire.encryption.keyset") Optional<String> keyset) {
        return CryptoService.fromConfig(keyset);
    }
}
