package dev.codespire.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every {@code @WebSocket} endpoint names who may open it.
 *
 * <p>The {@code /api/*} and {@code /gw/*} HTTP policies only ask for a signed-in identity, so a socket with no security
 * annotation of its own sends its data to anyone signed in, while the REST read of the same data asks for a role. Six
 * of seven dashboard sockets had that gap (PR #460); each was found by reading, never by a test. This scan makes the
 * next socket without an annotation fail the build. {@code @PermitAll} is allowed, so a public socket is a choice
 * written in the code rather than a default.
 */
class WebSocketsDeclareSecurityTest {

    private static final Pattern WEB_SOCKET = Pattern.compile("@WebSocket\\s*\\(");

    private static final Pattern SECURITY = Pattern.compile("@(RolesAllowed|Authenticated|PermitAll|DenyAll)\\b");

    @Test
    void everyWebSocketDeclaresWhoMayOpenIt() {
        List<String> open = new ArrayList<>();
        for (Path source : webSockets()) {
            if (!SECURITY.matcher(code(source)).find()) {
                open.add(relative(source));
            }
        }
        if (!open.isEmpty()) {
            fail(open.size() + " WebSocket endpoint(s) without a security annotation:\n\n  "
                    + String.join("\n  ", open)
                    + "\n\nThe HTTP policy only asks for a signed-in identity. Add the roles of the REST read "
                    + "that serves the same data, for example @RolesAllowed({\"spire-viewer\", \"spire-admin\"}), "
                    + "or @PermitAll if the socket is meant to be public.");
        }
    }

    /** Guards the guard: an empty scan and a detector that never matches both read as green. */
    @Test
    void theScanFindsTheKnownSocketsAndTheDetectorStillDetects() {
        List<String> found = webSockets().stream().map(WebSocketsDeclareSecurityTest::relative).toList();
        assertTrue(found.stream().anyMatch(p -> p.endsWith("orchestrator/ws/ReviewsSocket.java")), found.toString());
        assertTrue(found.stream().anyMatch(p -> p.endsWith("gateway/attention/WebhookAttentionSocket.java")),
                found.toString());
        assertFalse(SECURITY.matcher("@WebSocket(path = \"/api/ws/TEST\")\npublic class TestSocket {}").find(),
                "a socket with no annotation must not pass");
        assertFalse(SECURITY.matcher(JavaSource.withoutComments("/** @RolesAllowed in prose */ class TestSocket {}"))
                .find(), "an annotation named only in a comment must not count");
    }

    private static List<Path> webSockets() {
        try (Stream<Path> files = Files.walk(RootBuild.repoRoot())) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> relative(p).contains("/src/main/java/"))
                    .filter(p -> WEB_SOCKET.matcher(code(p)).find())
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String code(Path source) {
        try {
            return JavaSource.withoutComments(Files.readString(source));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String relative(Path source) {
        return RootBuild.repoRoot().relativize(source).toString().replace('\\', '/');
    }
}
