package dev.codespire.orchestrator.factory;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An operator-facing message may only send someone to a screen that exists.
 *
 * <p>Four refusals in {@link RunResource} said "Settings -&gt; Harness credentials". There has never
 * been such a screen: {@code /api/harness-credentials} has no Settings page, and the UI reads harness
 * credentials only on the Runs screens. The wording was written beside a plan for one, and nothing
 * failed when the plan did not ship — so the refusal that fires exactly when an operator is already
 * stuck sent them looking for a page that is not there.
 *
 * <p>The route list is <b>derived</b> from {@code App.tsx} rather than restated here, for the reason
 * {@code ContextAccountCompatibilityTest} gives about the account picker: a second copy of the answer
 * is a second thing that can drift, and the copy is the one nobody updates.
 */
class SettingsLinksNameRealScreensTest {

    /** Matches "Settings -> Name", "Settings → Name" and a trailing "-> Sub" (Settings -> LLM -> Models). */
    private static final Pattern LINK = Pattern.compile("Settings\\s*(?:->|→)\\s*([A-Za-z][A-Za-z ]*)");

    @Test
    void everySettingsLinkInAnOperatorMessageNamesARouteTheUiActuallyHas() throws IOException {
        Path root = repositoryRoot();
        Set<String> screens = screensFrom(root.resolve("spire-ui/src/App.tsx"));
        // Anti-vacuity: a parse that quietly matched nothing would let every link through.
        assertTrue(screens.contains("accounts") && screens.contains("llm") && screens.size() >= 6,
                "App.tsx route parse produced " + screens + "; the guard would pass over an empty list");

        List<String> dangling = new ArrayList<>();
        try (Stream<Path> sources = Files.walk(root.resolve("spire-orchestrator/src/main/java"))) {
            for (Path file : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                Matcher found = LINK.matcher(Files.readString(file));
                while (found.find()) {
                    // "Settings -> LLM -> Models" names the LLM screen; only the first hop is a route.
                    String screen = found.group(1).trim().split("\\s+")[0].toLowerCase(Locale.ROOT);
                    if (!screens.contains(screen)) {
                        dangling.add(root.relativize(file) + ": Settings -> " + found.group(1).trim());
                    }
                }
            }
        }
        assertEquals(List.of(), dangling,
                "these messages send an operator to a Settings screen that does not exist; "
                        + "known routes are " + screens);
    }

    /** The nav table in App.tsx: ['/settings/accounts', 'Accounts'], one entry per screen. */
    private static Set<String> screensFrom(Path appTsx) throws IOException {
        Matcher routes = Pattern.compile("'/settings/([a-z-]+)'").matcher(Files.readString(appTsx));
        Set<String> screens = new LinkedHashSet<>();
        while (routes.find()) {
            screens.add(routes.group(1));
        }
        return screens;
    }

    private static Path repositoryRoot() {
        Path candidate = Path.of("").toAbsolutePath();
        while (candidate != null && !Files.exists(candidate.resolve("spire-ui"))) {
            candidate = candidate.getParent();
        }
        if (candidate == null) {
            throw new IllegalStateException("no repository root above " + Path.of("").toAbsolutePath());
        }
        return candidate;
    }
}
