package dev.codespire.encryption;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tink AES-GCM round-trip, associated-data binding, and key handling (pure). */
class EncryptionServiceTest {

    private final EncryptionService crypto = new EncryptionService(EncryptionService.generateKeysetBase64());

    @Test
    void roundTripsStringWithAad() {
        String cipher = crypto.encryptString("sk-secret-token", "provider:42");
        assertNotEquals("sk-secret-token", cipher);
        assertEquals("sk-secret-token", crypto.decryptString(cipher, "provider:42"));
    }

    @Test
    void roundTripsBytes() {
        byte[] payload = "{\"event\":\"ReviewRequested\"}".getBytes();
        byte[] cipher = crypto.encrypt(payload, "review::acme/web#1");
        assertArrayEquals(payload, crypto.decrypt(cipher, "review::acme/web#1"));
    }

    @Test
    void wrongAadFailsToDecrypt() {
        String cipher = crypto.encryptString("secret", "provider:1");
        assertThrows(IllegalStateException.class, () -> crypto.decryptString(cipher, "provider:2"));
    }

    @Test
    void ciphertextIsNonDeterministic() {
        assertNotEquals(crypto.encryptString("x", "a"), crypto.encryptString("x", "a"));
    }

    @Test
    void aDifferentKeyCannotDecrypt() {
        String cipher = crypto.encryptString("secret", "aad");
        EncryptionService other = new EncryptionService(EncryptionService.generateKeysetBase64());
        assertThrows(IllegalStateException.class, () -> other.decryptString(cipher, "aad"));
    }

    @Test
    void missingKeysetFailsFast() {
        assertThrows(IllegalStateException.class, () -> EncryptionService.fromConfig(Optional.empty()));
    }

    @Test
    void nullOrBlankAadIsRejected() {
        // a null/blank AAD would silently drop the row binding — reject loudly
        byte[] bytes = "payload".getBytes();
        assertThrows(IllegalArgumentException.class, () -> crypto.encrypt(bytes, null));
        assertThrows(IllegalArgumentException.class, () -> crypto.encrypt(bytes, "  "));
        assertThrows(IllegalArgumentException.class, () -> crypto.encryptString("payload", null));
        assertThrows(IllegalArgumentException.class, () -> crypto.encryptString("payload", ""));

        String cipher = crypto.encryptString("payload", "row:1");
        assertThrows(IllegalArgumentException.class, () -> crypto.decryptString(cipher, null));
        assertThrows(IllegalArgumentException.class, () -> crypto.decryptString(cipher, " "));
        assertThrows(IllegalArgumentException.class,
                () -> crypto.decrypt(java.util.Base64.getDecoder().decode(cipher), ""));
    }
}
