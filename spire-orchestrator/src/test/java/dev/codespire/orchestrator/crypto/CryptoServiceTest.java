package dev.codespire.orchestrator.crypto;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Tink AES-GCM round-trip, associated-data binding, and key handling (pure). */
class CryptoServiceTest {

    private final CryptoService crypto = new CryptoService(CryptoService.generateKeysetBase64());

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
        CryptoService other = new CryptoService(CryptoService.generateKeysetBase64());
        assertThrows(IllegalStateException.class, () -> other.decryptString(cipher, "aad"));
    }

    @Test
    void missingKeysetFailsFast() {
        assertThrows(IllegalStateException.class, () -> new CryptoService(java.util.Optional.<String>empty()));
    }
}
