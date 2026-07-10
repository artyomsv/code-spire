package dev.codespire.gateway;

import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Singleton;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Optional;

/**
 * The gateway holds ONLY the dedicated webhook keyset — never the master keyset
 * that unlocks SCM/LLM API tokens. So it is the internet-facing edge that, if
 * compromised, can decrypt inbound webhook secrets (low value) but not the
 * credentials that call out to GitHub/LLM providers. Since it is the gateway's
 * single {@link EncryptionService}, no qualifier is needed. Fail-fast (ADR-009):
 * a missing keyset is a startup error naming the exact env var.
 */
@ApplicationScoped
public class GatewayEncryptionProducer {

    @Produces
    @Singleton
    public EncryptionService webhookCryptoService(
            @ConfigProperty(name = "spire.encryption.webhook-keyset") Optional<String> keyset) {
        return new EncryptionService(keyset.filter(s -> !s.isBlank()).orElseThrow(() -> new IllegalStateException(
                "spire.encryption.webhook-keyset is required (base64 Tink keyset) — generate one with "
                        + "EncryptionService.generateKeysetBase64() and set SPIRE_ENCRYPTION_WEBHOOK_KEYSET")));
    }
}
