package dev.codespire.agentimage;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The exit code is the half CI consumes, and it was the half nothing asserted.
 *
 * <p>{@code run} was split from {@code main} with a javadoc saying it was so a test could drive it
 * — and then no test did, which is the shape this repository already recorded for a package-private
 * method whose javadoc said "so a test can drive it" while nothing did. Worse, the split as first
 * written could not deliver: {@code run} built its own Docker client, so exits 0 and 1 were
 * unreachable without a daemon. It takes the report as a function now, and all three codes are
 * asserted here with no daemon at all.
 */
class AgentImageCliTest {

    private static final String IMAGE = "acme/agent:1";

    private record Streams(String out, String err, int code) {
    }

    private static Streams run(String[] args, Function<String, ConformanceReport> reports) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = AgentImageCli.run(args,
                new PrintStream(out, true, StandardCharsets.UTF_8),
                new PrintStream(err, true, StandardCharsets.UTF_8),
                reports);
        return new Streams(out.toString(StandardCharsets.UTF_8),
                err.toString(StandardCharsets.UTF_8), code);
    }

    private static ConformanceReport reportWith(ConformanceReport.Verification verification) {
        return new ConformanceReport(IMAGE, List.of(verification),
                List.of(new ConformanceReport.Declaration(Clauses.TOOLCHAIN, null, "needs the repo")));
    }

    @Test
    void aConformingImageExitsZeroAndPrintsTheReportOnStdout() {
        Streams result = run(new String[] {"verify", IMAGE},
                image -> reportWith(ConformanceReport.Verification.passed(Clauses.GIT, "present")));

        assertEquals(AgentImageCli.CONFORMS, result.code());
        assertTrue(result.out().contains("CONFORMS"), result.out());
        assertTrue(result.err().isEmpty(), "nothing on stderr for a clean run: " + result.err());
    }

    @Test
    void aFailedClauseExitsOne() {
        Streams result = run(new String[] {"verify", IMAGE},
                image -> reportWith(ConformanceReport.Verification.failed(Clauses.GIT, "absent")));

        assertEquals(AgentImageCli.DOES_NOT_CONFORM, result.code());
        assertTrue(result.out().contains("DOES NOT CONFORM"), result.out());
    }

    /**
     * The distinction the CLI's own comment promised and the code did not make.
     *
     * <p>A clause the checker could not answer is not the same as an image that is wrong, and a
     * pipeline treating them alike eventually fails a good image because a daemon was busy. The
     * report still shows the clause as a failure — omitting it would read as a shorter contract —
     * so this asserts the EXIT CODE, which is the part that differs.
     */
    @Test
    void aClauseThatCouldNotBeCheckedExitsTwoRatherThanOne() {
        Streams result = run(new String[] {"verify", IMAGE},
                image -> reportWith(AgentImageVerifier.unknown(Clauses.GIT, "daemon unreachable")));

        assertEquals(AgentImageCli.USAGE_OR_UNREACHABLE, result.code());
        assertTrue(result.out().contains("NOT CHECKED"), result.out());
    }

    @Test
    void anUnreachableImageExitsTwoAndSaysSoOnStderr() {
        Streams result = run(new String[] {"verify", IMAGE}, image -> {
            throw new IllegalStateException("no such image");
        });

        assertEquals(AgentImageCli.USAGE_OR_UNREACHABLE, result.code());
        assertTrue(result.err().contains("could not verify"), result.err());
        assertTrue(result.out().isEmpty(), "no partial report when nothing was checked");
    }

    /** Usage goes to stderr, so a pipeline capturing stdout gets a report or nothing. */
    @Test
    void everyBadInvocationPrintsUsageOnStderrAndExitsTwo() {
        for (String[] args : List.of(
                new String[] {},
                new String[] {"verify"},
                new String[] {"check", IMAGE},
                new String[] {"verify", "   "},
                new String[] {"verify", IMAGE, "extra"})) {
            Streams result = run(args, image -> {
                throw new AssertionError("a bad invocation must not reach the verifier");
            });

            assertEquals(AgentImageCli.USAGE_OR_UNREACHABLE, result.code(), String.join(" ", args));
            assertTrue(result.err().contains("usage:"), result.err());
            assertFalse(result.out().contains("agent image:"), "no report for a bad invocation");
        }
    }

    /** The usage text states the codes, because a pipeline author reads it before reading the docs. */
    @Test
    void theUsageTextStatesWhatEachExitCodeMeans() {
        String usage = run(new String[] {}, image -> {
            throw new AssertionError("not reached");
        }).err();

        assertTrue(usage.contains("0 conforms"), usage);
        assertTrue(usage.contains("1 does not conform"), usage);
        assertTrue(usage.contains("2 could not be checked"), usage);
    }
}
