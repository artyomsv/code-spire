package dev.codespire.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Every OIDC service must RENEW an expired session rather than send the browser back to the identity
 * provider.
 *
 * <p>Quarkus ships {@code token.refresh-expired} switched OFF, and the cost of leaving it off was
 * measured on 2026-09-10. Keycloak's default ID-token lifespan is five minutes; at every expiry
 * Quarkus invalidated the local session and auto-closed all three of the dashboard's WebSockets. The
 * SPA asked {@code /api/me} why, was answered {@code 499}, probed the sibling prefixes, found those
 * refused too, and assigned {@code window.location} to a login. The provider's own session was still
 * alive, so it re-authenticated with no prompt and returned to the same screen. The operator saw a
 * dashboard that reloaded itself every five minutes, said nothing about why, and discarded whatever
 * they had typed into a form. Full analysis:
 * {@code docs/superpowers/specs/2026-09-10-session-renewal-and-unsaved-work-design.md}.
 *
 * <p><b>Three settings, one change.</b> {@code refresh-expired} does nothing unless
 * {@code authentication.session-age-extension} is non-zero — Quarkus documents it as effective only
 * then — so a half-applied fix looks exactly like an applied one and behaves exactly like no fix at
 * all. That is why this guard requires all three together rather than the obvious one.
 *
 * <p><b>The service list is DERIVED, not declared.</b> A fifth deployable that adds
 * {@code auth-server-url} tomorrow and forgets renewal must fail here, which a hard-coded list would
 * not catch. {@link #theFourKnownDeployablesAreAmongThem()} then pins the floor, because a check that
 * scans zero files passes loudest right after someone moves them.
 */
class OidcSessionsAreRenewedTest {

    /** What marks a service as an OIDC client. Nothing else in the tree carries this key. */
    private static final String OIDC_CLIENT_MARKER = "auth-server-url:";

    /**
     * The deployables that had this defect. Not the input to the rule — the proof the rule has
     * something to measure.
     */
    private static final List<String> KNOWN_OIDC_SERVICES = List.of(
            "spire-gateway", "spire-orchestrator", "spire-review-worker", "spire-run-worker");

    /**
     * Each key, with the value it must NOT hold.
     *
     * <p>Stated as a negative as well as a positive because these files carry profile blocks
     * ({@code "%prod"}, {@code "%dev"}) that may re-declare a key further down. A plain "the file
     * mentions it" test would be satisfied by a top-level {@code true} that a profile below turns
     * off — which is the same silent-override shape as the {@code ${VAR}} with no default recorded
     * in {@code CLAUDE.md}.
     */
    private static final Pattern REFRESH_ON = Pattern.compile("^\\s*refresh-expired:\\s*true\\s*$", Pattern.MULTILINE);
    private static final Pattern REFRESH_OFF = Pattern.compile("^\\s*refresh-expired:\\s*false\\s*$", Pattern.MULTILINE);
    private static final Pattern SKEW = Pattern.compile("^\\s*refresh-token-time-skew:\\s*\\S+\\s*$", Pattern.MULTILINE);
    private static final Pattern AGE = Pattern.compile("^\\s*session-age-extension:\\s*(\\S+)\\s*$", Pattern.MULTILINE);

    @Test
    void everyOidcServiceRenewsItsSessionInsteadOfReAuthenticating() {
        Map<String, String> services = oidcServices();
        assertFalse(services.isEmpty(),
                "no application.yml declares " + OIDC_CLIENT_MARKER + ", so this test measures nothing");

        List<String> violations = new ArrayList<>();
        services.forEach((module, yaml) -> {
            if (!REFRESH_ON.matcher(yaml).find()) {
                violations.add(module + ": no `token.refresh-expired: true`. Its session dies at the "
                        + "ID token's exp — five minutes on a default Keycloak — and the dashboard "
                        + "answers by reloading the whole window, discarding unsaved form input.");
            }
            if (REFRESH_OFF.matcher(yaml).find()) {
                violations.add(module + ": a profile block turns `refresh-expired` back OFF. "
                        + "A per-profile exception to this needs its own reason, written here.");
            }
            if (!SKEW.matcher(yaml).find()) {
                violations.add(module + ": no `token.refresh-token-time-skew`. Without it the refresh "
                        + "happens AT the expiry rather than before it, so the session cookie is "
                        + "momentarily absent and a request in that window is still refused.");
            }
            var age = AGE.matcher(yaml);
            if (!age.find()) {
                violations.add(module + ": no `authentication.session-age-extension`. "
                        + "`refresh-expired` is inert without it, so renewal LOOKS configured and "
                        + "is not — the worst of the three ways to get this wrong.");
            } else {
                do {
                    String value = age.group(1);
                    if (value.matches("0[A-Za-z]*")) {
                        violations.add(module + ": `session-age-extension: " + value + "` is zero, "
                                + "which disables renewal exactly as omitting it would.");
                    }
                } while (age.find());
            }
        });

        if (!violations.isEmpty()) {
            fail("An OIDC service does not renew its session:\n\n  " + String.join("\n\n  ", violations)
                    + "\n\n  See docs/superpowers/specs/"
                    + "2026-09-10-session-renewal-and-unsaved-work-design.md");
        }
    }

    /**
     * The rule above scans what it finds. This says what it must find.
     *
     * <p>Without it, moving or renaming the four files would leave the rule vacuously satisfied — the
     * failure mode this repository has hit repeatedly, and the one that passes most convincingly.
     */
    @Test
    void theFourKnownDeployablesAreAmongThem() {
        List<String> found = new ArrayList<>(oidcServices().keySet());
        found.retainAll(KNOWN_OIDC_SERVICES);
        found.sort(String::compareTo);
        assertEquals(KNOWN_OIDC_SERVICES, found,
                "a known OIDC deployable no longer declares " + OIDC_CLIENT_MARKER
                        + " in its application.yml. If that is deliberate, this list is part of the "
                        + "change; if it is not, the renewal rule above just stopped checking it.");
    }

    /** Module name → its {@code application.yml} text, for every module that is an OIDC client. */
    private static Map<String, String> oidcServices() {
        Map<String, String> services = new LinkedHashMap<>();
        try (Stream<Path> modules = Files.list(repoRoot())) {
            modules.filter(Files::isDirectory)
                    .filter(dir -> dir.getFileName().toString().startsWith("spire-"))
                    .sorted()
                    .forEach(dir -> {
                        Path yaml = dir.resolve("src/main/resources/application.yml");
                        if (!Files.exists(yaml)) {
                            return;
                        }
                        String text = read(yaml);
                        if (text.contains(OIDC_CLIENT_MARKER)) {
                            services.put(dir.getFileName().toString(), text);
                        }
                    });
        } catch (IOException e) {
            throw new UncheckedIOException("could not list the repository root", e);
        }
        return services;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
    }

    /** The worktree root, found by walking up to the settings file rather than assuming a depth. */
    private static Path repoRoot() {
        Path here = Path.of("").toAbsolutePath();
        while (here != null && !Files.exists(here.resolve("settings.gradle.kts"))) {
            here = here.getParent();
        }
        if (here == null) {
            throw new IllegalStateException("could not find the repository root");
        }
        return here;
    }
}
