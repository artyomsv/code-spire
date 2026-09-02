package dev.codespire.runworker;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PublisherImageCheckTest {

    @Test
    void aMissingOrBlankImageIsAStartupRefusalNamingTheVariable() {
        // Without this the worker started clean and failed on its first run as a non-retryable
        // BAD_COMMAND, burning that subject. A refusal at startup costs nothing.
        for (Optional<String> absent : java.util.List.of(Optional.<String>empty(), Optional.of(" "))) {
            IllegalStateException refusal = assertThrows(IllegalStateException.class,
                    () -> PublisherImageCheck.verify(absent));
            assertTrue(refusal.getMessage().contains("SPIRE_PUBLISHER_IMAGE"), refusal.getMessage());
        }
    }

    @Test
    void aConfiguredImagePasses() {
        assertDoesNotThrow(() -> PublisherImageCheck.verify(Optional.of("spire-publisher:TEST")));
    }
}
