package dev.codespire.e2e.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Fixture repository contents, held as test resources so they are real reviewable files rather than
 * string literals — the line numbers in them are load-bearing, since the mock's canned findings cite
 * exact anchors.
 */
public final class Fixtures {

    private Fixtures() {
    }

    public static String read(String resourcePath) {
        try (InputStream stream = Fixtures.class.getClassLoader().getResourceAsStream(resourcePath)) {
            if (stream == null) {
                throw new IllegalStateException("no fixture at " + resourcePath);
            }
            // Normalised to LF: a Windows checkout would otherwise push CRLF content through the
            // GitLab Commits API, shifting nothing visibly but changing every diff line's bytes.
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8).replace("\r\n", "\n");
        } catch (IOException e) {
            throw new IllegalStateException("could not read fixture " + resourcePath, e);
        }
    }
}
