package dev.codespire.diff;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class LanguagesTest {

    @Test
    void mapsKnownExtensionsCaseInsensitively() {
        assertEquals("java", Languages.of("src/App.java"));
        assertEquals("java", Languages.of("SRC/APP.JAVA"));
        assertEquals("typescript", Languages.of("web/main.tsx"));
        assertEquals("yaml", Languages.of("config/app.yml"));
    }

    @Test
    void unknownAndEdgeCasesFallBackToUnknown() {
        assertEquals("unknown", Languages.of(null));
        assertEquals("unknown", Languages.of("Makefile"));
        assertEquals("unknown", Languages.of("file."));
        assertEquals("unknown", Languages.of("archive.xyz"));
    }
}
