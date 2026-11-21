package dev.codespire.orchestrator.llm.catalog;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/** The catalog copy built into the image (processResources), read from the classpath. */
final class BundledCatalog {

    private BundledCatalog() {
    }

    /** The text of the bundled copy, or null when the image carries none. */
    static String read(String resource) {
        ClassLoader loader = Thread.currentThread().getContextClassLoader();
        try (InputStream in = loader.getResourceAsStream(resource)) {
            return in == null ? null : new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("The bundled catalog " + resource + " could not be read", e);
        }
    }
}
