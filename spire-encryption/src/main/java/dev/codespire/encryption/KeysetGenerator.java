package dev.codespire.encryption;

/**
 * Prints a fresh base64 Tink AES-256-GCM keyset to stdout — for
 * {@code SPIRE_ENCRYPTION_KEYSET} and {@code SPIRE_ENCRYPTION_WEBHOOK_KEYSET}.
 * Run via the {@code :spire-encryption:generateKeyset} Gradle task. Generate a
 * SEPARATE keyset per variable; never reuse one for both.
 */
public final class KeysetGenerator {

    private KeysetGenerator() {
    }

    public static void main(String[] args) {
        System.out.println(EncryptionService.generateKeysetBase64());
    }
}
