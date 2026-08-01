package dev.codespire.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Following a redirect by hand is a security decision, and it belongs in one place.
 *
 * <p>A manual redirect loop is where the SSRF guard lives: the bot's Authorization header must reach
 * only the configured API host, a cross-host hop into loopback/link-local/private space is refused,
 * and an unparseable {@code Location} must fail as the adapter's own exception type rather than a raw
 * {@code IllegalArgumentException} that escapes every caller's classification. Each of those was a
 * real defect at some point, and each had to be fixed in every copy.
 *
 * <p>{@code spire-http}'s {@code PinnedJsonClient} is that one place. This check does not force every
 * caller onto it — the SCM clients legitimately need more than it offers (see {@link #ALLOWED}) — it
 * makes a NEW copy fail the build, so the count can only go down.
 *
 * <p>Refusing redirects outright is not a copy and is not flagged: a client that sets
 * {@code Redirect.NEVER} and never reads {@code Location} has taken the safest option available and
 * has no guard to drift. Both key validators do exactly that.
 */
class RedirectHandlingHasOneHomeTest {

    /** Where the shared implementation lives; everything else is measured against it. */
    private static final String ONE_HOME =
            "spire-http/src/main/java/dev/codespire/http/PinnedJsonClient.java";

    /**
     * Hand-rolled loops that predate the shared client, each with what blocks the migration.
     *
     * <p>An entry is a debt marker, not an exemption in principle. The tracked resolution is to move
     * these across when they are next opened for other work, which needs {@code PinnedJsonClient} to
     * grow the capabilities named below first.
     */
    private static final Map<String, String> ALLOWED = allowlist();

    private static Map<String, String> allowlist() {
        Map<String, String> allowed = new LinkedHashMap<>();
        String scmNeeds = "Predates spire-http and needs more than PinnedJsonClient offers: POST/PUT with "
                + "JSON bodies for comment posting, non-JSON GETs, and per-provider Retry-After "
                + "extraction. Migrating means growing the shared client first — tracked in techdebt.";
        allowed.put("spire-scm-bitbucket/src/main/java/dev/codespire/scm/bitbucket/BitbucketCloudClient.java",
                scmNeeds);
        allowed.put("spire-scm-github/src/main/java/dev/codespire/scm/github/GitHubClient.java",
                scmNeeds + " Also posts GraphQL and reads raw file content.");
        allowed.put("spire-scm-gitlab/src/main/java/dev/codespire/scm/gitlab/GitLabClient.java",
                scmNeeds + " Also reads raw file content.");
        return Collections.unmodifiableMap(allowed);
    }

    @Test
    void noNewModuleFollowsRedirectsByHand() {
        List<String> copies = new ArrayList<>();
        for (Path source : moduleSources()) {
            String relative = relative(source);
            if (!relative.equals(ONE_HOME) && !ALLOWED.containsKey(relative) && followsRedirects(source)) {
                copies.add(relative);
            }
        }
        if (!copies.isEmpty()) {
            fail(report(copies));
        }
    }

    /**
     * An allowlist entry that no longer describes a redirect loop means the file was migrated or
     * deleted — the entry must go with it, or the next hand-rolled loop in that same file is waved
     * through. This is the check that lets the count go down and stay down.
     */
    @Test
    void theAllowlistCarriesNoStaleEntries() {
        List<String> stale = new ArrayList<>();
        for (String relative : ALLOWED.keySet()) {
            Path source = repoRoot().resolve(relative);
            if (!Files.isRegularFile(source)) {
                stale.add(relative + " — file no longer exists");
            } else if (!followsRedirects(source)) {
                stale.add(relative + " — no longer follows redirects by hand, so drop the entry");
            }
        }
        if (!stale.isEmpty()) {
            fail("Stale ALLOWED entries in " + RedirectHandlingHasOneHomeTest.class.getSimpleName()
                    + ":\n\n  " + String.join("\n  ", stale)
                    + "\n\nOne copy fewer is the goal — record it by removing the entry.\n");
        }
    }

    /** Guards the guard: a wrong root or a moved shared client would make the scan find nothing. */
    @Test
    void theScanReachesTheSharedClientAndEveryModule() {
        List<Path> sources = moduleSources();
        assertTrue(sources.stream().anyMatch(p -> relative(p).equals(ONE_HOME)),
                "the shared client was not scanned — has " + ONE_HOME + " moved?");
        assertTrue(followsRedirects(repoRoot().resolve(ONE_HOME)),
                "the shared client no longer looks like a redirect follower — the detector below is "
                        + "matching on something that has changed, so every other file now looks clean");
        assertTrue(sources.size() > 200, "expected the repo's sources, scanned only " + sources.size());
    }

    /**
     * A file follows redirects by hand when it both declines the JDK's automatic handling and reads
     * the {@code Location} header itself. Requiring BOTH is what keeps a client that merely refuses
     * redirects — the safest posture, with no guard of its own to maintain — from being reported as a
     * duplicate of a guard it does not have.
     */
    private static boolean followsRedirects(Path source) {
        String code = JavaSource.withoutComments(read(source));
        return code.contains("Redirect.NEVER") && code.contains("\"Location\"");
    }

    private static String report(List<String> copies) {
        StringBuilder message = new StringBuilder();
        message.append(copies.size()).append(" new hand-rolled redirect loop(s):\n\n");
        copies.forEach(copy -> message.append("  ").append(copy).append("\n"));
        message.append("""

                Following redirects by hand carries the SSRF guard with it: the Authorization header
                must be pinned to the configured API host, a cross-host hop into loopback/link-local/
                private space must be refused, and an unparseable Location must surface as the
                adapter's own exception type. Every one of those started as a bug, and every fix had
                to land in each copy.

                Resolve one of these ways, in order of preference:

                  1. Use spire-http's PinnedJsonClient. It is the one home for this guard, and it
                     already backs four context adapters.
                  2. Do not follow redirects at all — set Redirect.NEVER and treat a 3xx as a failure.
                     Both key validators do this, and it needs no guard because it takes no hop.
                  3. If the shared client genuinely lacks what you need, grow IT and use it. Adding a
                     capability there fixes every caller at once, which is the whole point.
                """);
        return message.toString();
    }

    private static Path repoRoot() {
        String root = System.getProperty("spire.repoRoot");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("spire.repoRoot is unset — the Gradle test task must pass it "
                    + "(see spire-arch/build.gradle.kts)");
        }
        return Path.of(root);
    }

    /** Every module's production sources — a copy is worth catching wherever it appears. */
    private static List<Path> moduleSources() {
        List<Path> sources = new ArrayList<>();
        try (Stream<Path> modules = Files.list(repoRoot())) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path main = module.resolve("src/main/java");
                if (!Files.isDirectory(main)) {
                    continue;
                }
                try (Stream<Path> tree = Files.walk(main)) {
                    tree.filter(path -> path.toString().endsWith(".java")).forEach(sources::add);
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot walk the repository", e);
        }
        return sources;
    }

    private static String relative(Path source) {
        return repoRoot().relativize(source).toString().replace('\\', '/');
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + source, e);
        }
    }
}
