package dev.codespire.orchestrator.crypto;

import com.google.crypto.tink.Aead;
import com.google.crypto.tink.InsecureSecretKeyAccess;
import com.google.crypto.tink.KeysetHandle;
import com.google.crypto.tink.RegistryConfiguration;
import com.google.crypto.tink.TinkJsonProtoKeysetFormat;
import com.google.crypto.tink.aead.AeadConfig;
import com.google.crypto.tink.aead.PredefinedAeadParameters;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Base64;
import java.util.Optional;

/**
 * Encryption at rest (ADR-009 / SECURITY.md) — Google Tink AES-256-GCM. The
 * master keyset is the ONE bootstrap secret (env {@code SPIRE_ENCRYPTION_KEYSET},
 * base64 of a Tink JSON keyset); everything sensitive in the DB is encrypted with
 * it. Associated data (a stable row identifier) binds a ciphertext to its row, so
 * it cannot be swapped to another. The orchestrator is the only KEK holder.
 *
 * <p>Generate a keyset with {@link #generateKeysetBase64()}.
 */
@ApplicationScoped
public class CryptoService {

    static {
        try {
            AeadConfig.register();
        } catch (GeneralSecurityException e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private final Aead aead;

    @Inject
    public CryptoService(@ConfigProperty(name = "spire.encryption.keyset") Optional<String> keyset) {
        this(keyset.filter(s -> !s.isBlank()).orElseThrow(() -> new IllegalStateException(
                "spire.encryption.keyset is required (base64 Tink keyset) — generate one with "
                        + "CryptoService.generateKeysetBase64() and set SPIRE_ENCRYPTION_KEYSET")));
    }

    /** Direct construction from a base64 keyset (also used by tests). */
    public CryptoService(String keysetBase64) {
        this.aead = loadAead(keysetBase64);
    }

    /** Encrypt bytes (e.g. an event payload). {@code aad} binds it to its row. */
    public byte[] encrypt(byte[] plaintext, String aad) {
        try {
            return aead.encrypt(plaintext, aadBytes(aad));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("encryption failed", e);
        }
    }

    public byte[] decrypt(byte[] ciphertext, String aad) {
        try {
            return aead.decrypt(ciphertext, aadBytes(aad));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("decryption failed", e);
        }
    }

    /** Encrypt a string to base64 ciphertext (for TEXT columns). */
    public String encryptString(String plaintext, String aad) {
        return Base64.getEncoder().encodeToString(encrypt(plaintext.getBytes(StandardCharsets.UTF_8), aad));
    }

    public String decryptString(String ciphertextBase64, String aad) {
        return new String(decrypt(Base64.getDecoder().decode(ciphertextBase64), aad), StandardCharsets.UTF_8);
    }

    private static Aead loadAead(String keysetBase64) {
        try {
            String json = new String(Base64.getDecoder().decode(keysetBase64), StandardCharsets.UTF_8);
            KeysetHandle handle = TinkJsonProtoKeysetFormat.parseKeyset(json, InsecureSecretKeyAccess.get());
            return handle.getPrimitive(RegistryConfiguration.get(), Aead.class);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Invalid spire.encryption.keyset", e);
        }
    }

    private static byte[] aadBytes(String aad) {
        return aad == null ? new byte[0] : aad.getBytes(StandardCharsets.UTF_8);
    }

    /** Generate a fresh AES-256-GCM keyset, base64-encoded — for SPIRE_ENCRYPTION_KEYSET. */
    public static String generateKeysetBase64() {
        try {
            AeadConfig.register();
            KeysetHandle handle = KeysetHandle.generateNew(PredefinedAeadParameters.AES256_GCM);
            String json = TinkJsonProtoKeysetFormat.serializeKeyset(handle, InsecureSecretKeyAccess.get());
            return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("keyset generation failed", e);
        }
    }
}
