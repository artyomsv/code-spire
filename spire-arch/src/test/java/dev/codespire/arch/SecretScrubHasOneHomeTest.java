package dev.codespire.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Redacting a credential from text is a security decision, and it belongs in one place.
 *
 * <p>{@code spire-secrets}' {@code SecretScrub} is that place. This does not force every caller
 * through it — it makes a NEW hand-rolled scrubber fail the build, so the count can only go down.
 *
 * <p><b>Written because the argument for consolidating already existed and the guard did not.</b>
 * {@code SecretScrub}'s own class javadoc cites {@code RedirectHandlingHasOneHomeTest} as precedent
 * and says "a credential scrubber is the stronger case"; {@code OutcomeWriterTest} then rests on
 * that, asserting the publisher reaches the shared class "structurally — its own copy is deleted".
 * Neither was enforced by anything. Worse, the moment the two implementations stopped differing
 * behaviourally was the moment a re-added local copy stopped failing any test.
 *
 * <p>That gap was not theoretical. Two hand-rolled scrubbers with rules that quietly differed is
 * exactly what {@code techdebt/global/3-2-two-credential-scrubbers-with-divergent-rules.md} recorded,
 * and the weaker of the two ran in the container holding the git write token: no length floor, no
 * ordering, one credential. Both were correct-looking, and a fix applied to one never reached the
 * other.
 *
 * <p><b>What it detects.</b> A file that names redaction AND replaces text is a scrubber. Both
 * halves are needed: naming redaction alone catches a caller that merely passes text to the shared
 * class, and calling {@code replace} alone catches most string handling in the repository. Comments
 * are stripped first, so prose about redaction is not a finding — only code is.
 */
class SecretScrubHasOneHomeTest {

    /** Where the shared implementation lives; everything else is measured against it. */
    private static final String ONE_HOME =
            "spire-secrets/src/main/java/dev/codespire/secrets/SecretScrub.java";

    /**
     * Hand-rolled scrubbers that predate the shared class.
     *
     * <p>Empty, and that is the point: both copies were consolidated. An entry here is a statement
     * that a second set of redaction rules is acceptable, which is the condition this guard exists
     * to refuse — so adding one needs the argument written beside it.
     */
    private static final Set<String> ALLOWED = Set.of();

    @Test
    void noNewModuleRedactsCredentialsByHand() {
        List<String> copies = new ArrayList<>();
        for (Path source : mainSources()) {
            String relative = relative(source);
            if (!relative.equals(ONE_HOME) && !ALLOWED.contains(relative) && redactsByHand(source)) {
                copies.add(relative);
            }
        }
        if (!copies.isEmpty()) {
            fail(report(copies));
        }
    }

    /**
     * Guards the guard, and it matters more here than usual.
     *
     * <p>With an empty allowlist, "no copies found" and "the detector stopped detecting" produce the
     * same green. So the detector is run against the one home, which must always trip it, and against
     * a string built here that must not.
     */
    @Test
    void theScanReachesTheSharedClassAndItsDetectorStillDetects() {
        List<Path> sources = mainSources();
        assertTrue(sources.size() > 200, "expected the repo's main sources, scanned only " + sources.size());
        assertTrue(sources.stream().anyMatch(p -> relative(p).equals(ONE_HOME)),
                "the shared scrubber was not scanned — has " + ONE_HOME + " moved?");
        assertTrue(redactsByHand(RootBuild.repoRoot().resolve(ONE_HOME)),
                "the shared scrubber no longer looks like one to this detector, so every other file "
                        + "now looks clean — the detector is matching on something that has changed");
    }

    /**
     * A file both NAMES redaction and REPLACES text.
     *
     * <p>Comments are stripped first, so a javadoc explaining that something is redacted elsewhere is
     * not a finding. What is left is an identifier or a literal — which is what a marker constant and
     * its use look like.
     */
    private static boolean redactsByHand(Path source) {
        String code = JavaSource.withoutComments(read(source)).toLowerCase(Locale.ROOT);
        return code.contains("redact") && code.contains(".replace(");
    }

    private static String report(List<String> copies) {
        return copies.size() + " hand-rolled credential scrubber(s):\n\n  "
                + String.join("\n  ", copies)
                + """


                Redacting a credential carries its rules with it: every form the secret takes (the
                literal, percent-encoded, and base64(user:secret)), longest-first ordering so one
                secret containing another is fully removed, and the decision about what to do with a
                secret too short to redact safely. Two copies of that had already drifted apart once
                — the weaker one ran in the container holding the git write token — and a fix applied
                to one did not reach the other.

                Use spire-secrets' SecretScrub. It depends on the JDK and nothing else, precisely so
                that an FSL service and an Apache library can both consume it.
                """;
    }

    private static List<Path> mainSources() {
        try (Stream<Path> files = Files.walk(RootBuild.repoRoot())) {
            return files.filter(p -> p.toString().endsWith(".java"))
                    .filter(p -> relative(p).contains("/src/main/java/"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String relative(Path source) {
        return RootBuild.repoRoot().relativize(source).toString().replace('\\', '/');
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
