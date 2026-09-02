package dev.codespire.contract.event;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * FR-F9: every failed run carries a discriminated cause from a closed set, recorded as data.
 *
 * <p>"Read the logs" is not a failure cause, and neither is a free string. Before this, a cause could
 * come from three places with no agreement between them: three literals in the launcher, the twelve
 * values of the harness's own {@code FailureCause}, and — the open one — whatever string the
 * publisher happened to write into its JSON, defaulted to {@code PUBLISHER_FAILED} when absent. The
 * read model stored the result in an unconstrained {@code VARCHAR(32)}, so a typo in any writer
 * became a category no query would ever match and no operator would ever see grouped.
 *
 * <p><b>The set is closed, but parsing is lenient, and the two are not in tension.</b> An unknown
 * value maps to {@link RunFailureCause#UNCLASSIFIED} rather than throwing, because the alternative
 * has already cost this project a paid run: a value the pipeline could not accept threw inside a
 * result handler and dead-lettered a review that had already been charged for. A failure to
 * classify must never become a second failure.
 */
class RunFailureCauseTest {

    @Test
    void anUnknownCauseIsUnclassifiedRatherThanAThrow() {
        // The lesson from the negative token count that dead-lettered a paid review: a value we
        // cannot interpret is answered, not thrown, at the boundary that receives it.
        assertEquals(RunFailureCause.UNCLASSIFIED, RunFailureCause.of("WHATEVER_THE_PUBLISHER_WROTE"));
        assertEquals(RunFailureCause.UNCLASSIFIED, RunFailureCause.of(null));
        assertEquals(RunFailureCause.UNCLASSIFIED, RunFailureCause.of(""));
        assertEquals(RunFailureCause.UNCLASSIFIED, RunFailureCause.of("   "));
    }

    @Test
    void aKnownCauseParsesBackToItself() {
        for (RunFailureCause cause : RunFailureCause.values()) {
            assertEquals(cause, RunFailureCause.of(cause.name()), cause.name());
        }
    }

    @Test
    void unclassifiedIsNeverWhatAWriterChooses() {
        // It is the reader's landing spot for a value it did not recognise. A writer selecting it
        // deliberately would be recording "we did not look", which is the thing FR-F9 forbids.
        assertFalse(RunFailureCause.UNCLASSIFIED.isChoosable(),
                "UNCLASSIFIED exists so an unknown wire value has somewhere to land, not so a "
                        + "writer can decline to classify");
        for (RunFailureCause cause : RunFailureCause.values()) {
            if (cause != RunFailureCause.UNCLASSIFIED) {
                assertTrue(cause.isChoosable(), cause + " must be choosable by a writer");
            }
        }
    }

    @Test
    void retryabilityIsAPropertyOfTheCauseNotOfTheCaller() {
        // Before this every publisher failure was reported retryable, so a run refused for a reason
        // that will refuse it again next time was retried at full cost.
        assertFalse(RunFailureCause.BAD_COMMAND.isRetryable(),
                "the same command will be rejected the same way");
        assertFalse(RunFailureCause.GATE_REFUSED.isRetryable(),
                "the gate refuses the same tree the same way; retrying spends an agent run to learn it twice");
        assertFalse(RunFailureCause.NON_FAST_FORWARD.isRetryable(),
                "the branch moved under the run; a retry pushes the same stale parent again");
        assertFalse(RunFailureCause.CREDENTIAL_REJECTED.isRetryable(),
                "a rejected credential is an answer, not a blip — retrying spends a request to be told again");
        assertTrue(RunFailureCause.IMAGE_UNAVAILABLE.isRetryable(), "a registry blip is transient");
        assertTrue(RunFailureCause.SANDBOX_LOST.isRetryable(), "an evicted sandbox can be replaced");
        assertTrue(RunFailureCause.RUNTIME_UNAVAILABLE.isRetryable(), "a daemon comes back");
    }

    @Test
    void aProviderOutageAndAnAgentThatFailedGetOppositeAnswers() {
        // The reason MODEL_UNAVAILABLE exists as its own value. An earlier draft folded the harness's
        // PROVIDER_ERROR, NO_MODEL_RESPONSE and HARNESS_EXIT_NONZERO into one non-retryable cause,
        // which quietly took the retry away from the outage that had earned it. Asserted from the
        // harness's own words, because that is the form the wire actually carries.
        assertTrue(RunFailureCause.of("PROVIDER_ERROR").isRetryable(),
                "a provider outage clears, and the run has not had its answer yet");
        assertTrue(RunFailureCause.of("NO_MODEL_RESPONSE").isRetryable(),
                "the model returned nothing at all, which is the same outage in another shape");
        assertFalse(RunFailureCause.of("HARNESS_EXIT_NONZERO").isRetryable(),
                "the agent ran to completion and failed; the same prompt fails the same way, "
                        + "and the model has already been paid for");

        assertNotEquals(RunFailureCause.of("PROVIDER_ERROR"), RunFailureCause.of("HARNESS_EXIT_NONZERO"),
                "collapsing these two is what made the retry decision wrong");
    }

    @Test
    void aCauseFitsTheColumnItIsStoredIn() {
        // factory_run.failure_cause is VARCHAR(32). A longer name would be rejected by Postgres at
        // the moment a run failed, turning a classified failure into an unrecorded one.
        for (RunFailureCause cause : RunFailureCause.values()) {
            assertTrue(cause.name().length() <= 32,
                    cause + " is " + cause.name().length() + " characters and will not fit failure_cause");
        }
    }

    /**
     * The union of every vocabulary that can reach the wire must be representable.
     *
     * <p>Scanned rather than listed, because a list checked against itself passes forever. The three
     * sources are the harness's own enum, the literals the worker constructs a {@code RunFailed}
     * with, and the literals the publisher writes into its outcome JSON — which used to arrive as an
     * arbitrary string and is the reason the set was open at all.
     */
    @Test
    void everyCauseAnyProducerCanEmitMapsIntoTheClosedSet() throws IOException {
        Set<String> emitted = causesEmittedAcrossTheRepository();

        assertFalse(emitted.isEmpty(),
                "the scan found no emitted cause, so it is asserting nothing — the producers no "
                        + "longer write causes in a shape this test recognises");

        Set<String> unmapped = new TreeSet<>();
        for (String cause : emitted) {
            if (RunFailureCause.of(cause) == RunFailureCause.UNCLASSIFIED) {
                unmapped.add(cause);
            }
        }
        assertEquals(Set.of(), unmapped,
                "every cause a producer can emit must map to a value in the closed set, or it "
                        + "reaches an operator as UNCLASSIFIED — which is FR-F9's 'read the logs' by "
                        + "another name. Unmapped: " + unmapped);
    }

    @Test
    void theScanSeesTheProducersItClaimsTo() throws IOException {
        // Guards the guard. The assertion above is satisfied by an empty scan, and the scan reads
        // source text, so a producer that changes how it names a cause becomes invisible rather
        // than failing. These three are the shapes that exist today.
        Set<String> emitted = causesEmittedAcrossTheRepository();

        assertTrue(emitted.contains("BAD_COMMAND"), "the launcher's own literals are not being seen");
        assertTrue(emitted.contains("PUSH_GATE_REFUSED"), "the harness enum is not being seen");
        assertTrue(emitted.contains("BUNDLE_UNREADABLE"), "the publisher's literals are not being seen");
        assertNotEquals(0, emitted.size());
    }

    /** Every SCREAMING_CASE token a producer pairs with a cause field, across the three sources. */
    private static Set<String> causesEmittedAcrossTheRepository() throws IOException {
        Set<String> causes = new TreeSet<>();
        Path root = repoRoot();

        // 1. The harness's own vocabulary, which the worker forwards by name().
        Path harnessEnum = root.resolve("spire-harness/src/main/java/dev/codespire/harness/FailureCause.java");
        if (Files.isRegularFile(harnessEnum)) {
            causes.addAll(screamingTokensIn(bodyOf(harnessEnum)));
        }

        // 2 and 3. The worker's RunFailed literals and the publisher's outcome JSON literals.
        for (String producer : List.of("spire-run-worker", "spire-publisher")) {
            Path main = root.resolve(producer).resolve("src/main/java");
            if (!Files.isDirectory(main)) {
                continue;
            }
            try (Stream<Path> sources = Files.walk(main)) {
                for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                    causes.addAll(causeLiteralsIn(Files.readString(source)));
                }
            }
        }
        return causes;
    }

    private static final Pattern ENUM_CONSTANT = Pattern.compile("\\b([A-Z][A-Z0-9]*(?:_[A-Z0-9]+)+)\\b");

    /**
     * A literal in a CAUSE position, not merely a SCREAMING_CASE string.
     *
     * <p>Matching every quoted upper-case token swept in environment-variable names and label keys,
     * which are not causes and never reach the wire as one. The three shapes below are how a cause
     * is actually written today: the second argument of a {@code RunFailed}, the first argument of
     * the publisher's {@code failed(...)}, and the default the worker reads when the publisher's
     * JSON carries none.
     */
    private static final Pattern CAUSE_POSITION = Pattern.compile(
            "RunFailed\\([^;]*?\"([A-Z][A-Z0-9_]+)\""
                    // The launcher's own helper, which every failure path there now routes through.
                    // Adding it was not optional: consolidating those paths onto one method made the
                    // RunFailed shape above stop matching, and this scan went blind to a whole
                    // producer while still passing. theScanSeesTheProducersItClaimsTo caught it.
                    + "|\\bfailure\\([^;]*?\"([A-Z][A-Z0-9_]+)\""
                    + "|\\bfailed\\(\\s*\"([A-Z][A-Z0-9_]+)\""
                    + "|asText\\(\\s*\"([A-Z][A-Z0-9_]+)\""
                    + "|NON_TERMINAL_CAUSES[^;]*?\"([A-Z][A-Z0-9_]+)\"",
            Pattern.DOTALL);

    private static Set<String> screamingTokensIn(String body) {
        return tokens(ENUM_CONSTANT.matcher(body));
    }

    private static Set<String> causeLiteralsIn(String source) {
        Set<String> found = new TreeSet<>();
        Matcher matcher = CAUSE_POSITION.matcher(source);
        while (matcher.find()) {
            for (int group = 1; group <= matcher.groupCount(); group++) {
                if (matcher.group(group) != null) {
                    found.add(matcher.group(group));
                }
            }
        }
        return found;
    }

    private static Set<String> tokens(Matcher matcher) {
        Set<String> found = new TreeSet<>();
        while (matcher.find()) {
            found.add(matcher.group(1));
        }
        return found;
    }

    /** The enum's constants, without its package line, imports or javadoc. */
    private static String bodyOf(Path enumSource) throws IOException {
        String src = Files.readString(enumSource);
        int brace = src.indexOf('{');
        return brace < 0 ? "" : src.substring(brace);
    }

    private static Path repoRoot() {
        Path here = Path.of("").toAbsolutePath();
        for (Path candidate = here; candidate != null; candidate = candidate.getParent()) {
            if (Files.isRegularFile(candidate.resolve("settings.gradle.kts"))) {
                return candidate;
            }
        }
        throw new IllegalStateException("no repository root above " + here);
    }
}
