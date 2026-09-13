package dev.codespire.contract.scm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ForgeOriginTest {
    @Test void canonicalizesApiPathsAndDefaultPorts() {
        assertEquals("https://forge.example.test", ForgeOrigin.of("HTTPS://FORGE.example.test:443/api/v4"));
        assertEquals("http://forge.example.test:8080", ForgeOrigin.of("http://forge.example.test:8080/api"));
        assertNotEquals(ForgeOrigin.of("https://a.example.test"), ForgeOrigin.of("https://b.example.test"));
    }
    @Test void rejectsAmbiguousOrSecretBearingUrls() {
        for (String value : new String[]{"", "forge.example.test", "ftp://forge.example.test", "https://user:secret@forge.example.test",
                "https://forge.example.test?token=TEST-secret", "https://forge.example.test#fragment"}) {
            assertThrows(IllegalArgumentException.class, () -> ForgeOrigin.of(value));
        }
    }
}
