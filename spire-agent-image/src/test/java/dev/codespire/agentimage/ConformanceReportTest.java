package dev.codespire.agentimage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The report's shape IS the requirement (FR-F13).
 *
 * <p>"Reports verified and declared clauses separately" is the headline, and a headline is exactly
 * the kind of claim that survives in prose while the code drifts. These assert the separation
 * structurally rather than by reading rendered text.
 */
class ConformanceReportTest {

    private static ConformanceReport report(List<ConformanceReport.Verification> verified,
                                            List<ConformanceReport.Declaration> declared) {
        return new ConformanceReport("acme/agent:1", verified, declared);
    }

    /**
     * The headline requirement, asserted directly: the two halves are separate lists, and nothing
     * from one appears in the other.
     */
    @Test
    void declaredClausesAreReportedSeparatelyFromVerifiedOnes() {
        ConformanceReport report = report(
                List.of(ConformanceReport.Verification.passed(Clauses.GIT, "a git binary is on PATH")),
                List.of(new ConformanceReport.Declaration(Clauses.TOOLCHAIN, "node", "needs the repo")));

        String rendered = report.render();
        int verifiedHeading = rendered.indexOf("VERIFIED");
        int declaredHeading = rendered.indexOf("DECLARED");

        assertTrue(verifiedHeading >= 0 && declaredHeading > verifiedHeading, rendered);
        assertTrue(rendered.indexOf(Clauses.GIT) < declaredHeading,
                "a verified clause must not appear under the declared heading");
        assertTrue(rendered.indexOf(Clauses.TOOLCHAIN) > declaredHeading,
                "a declared clause must not appear under the verified heading");
        assertTrue(rendered.contains("did NOT verify"),
                "the declared heading must say so; a reader who skims must not take it as proof");
    }

    /**
     * The failure mode the split exists to prevent, asserted where it cannot be argued with.
     *
     * <p>A single clause type carrying an {@code assurance} field would leave "report a declared
     * clause as verified" one line away, and that line would look correct. It is inexpressible
     * instead: {@link ConformanceReport.Declaration} has no pass/fail component to set. Asserted by
     * reflection, because a test that merely built a correct report would pass just as well against
     * a type that had one.
     */
    @Test
    void aDeclaredButUnverifiableClauseIsNeverReportedAsVerified() {
        List<String> declarationComponents =
                Arrays.stream(ConformanceReport.Declaration.class.getRecordComponents())
                        .map(RecordComponent::getName)
                        .toList();

        assertEquals(List.of("id", "claimed", "whyNotVerifiable"), declarationComponents,
                "a declaration records what the image SAID; a passed/verified component would make "
                        + "'this was proved' a value somebody can set");

        assertFalse(Clauses.VERIFIED.stream().anyMatch(Clauses.DECLARED::contains),
                "no clause may be in both lists, or one run could report it either way");
    }

    /** Conformance is the verified half only. A declaration cannot make an image fail or pass. */
    @Test
    void aDeclarationNeverDecidesConformance() {
        ConformanceReport withNoLabels = report(
                List.of(ConformanceReport.Verification.passed(Clauses.GIT, "present")),
                List.of(new ConformanceReport.Declaration(Clauses.TOOLCHAIN, null, "needs the repo")));

        assertTrue(withNoLabels.conforms(),
                "an image that declares nothing still conforms if every verified clause passed");

        ConformanceReport withEveryLabel = report(
                List.of(ConformanceReport.Verification.failed(Clauses.GIT, "absent")),
                List.of(new ConformanceReport.Declaration(Clauses.TOOLCHAIN, "node", "needs the repo")));

        assertFalse(withEveryLabel.conforms(),
                "and a full set of labels cannot rescue a failed verified clause");
    }

    /** A missing label is reported as absent, not as a failure — there is nothing to fail. */
    @Test
    void aMissingLabelIsReportedAsAbsentRatherThanAsAFailure() {
        String rendered = report(
                List.of(ConformanceReport.Verification.passed(Clauses.GIT, "present")),
                List.of(new ConformanceReport.Declaration(Clauses.HARNESS, null, "needs a paid call")))
                .render();

        assertTrue(rendered.contains("(no label)"), rendered);
        assertFalse(rendered.contains("FAIL"), "an absent declaration is not a conformance failure");
    }

    /**
     * A failure names the clause, not just "failed".
     *
     * <p>An operator holding a conformance failure has to be able to fix the image; a report saying
     * only that something went wrong sends them to read this checker's source.
     */
    @Test
    void aFailureNamesTheClauseAndWhatWasObserved() {
        ConformanceReport report = report(
                List.of(ConformanceReport.Verification.failed(Clauses.NON_ROOT, "runs as root (USER=<unset>)")),
                List.of());

        assertFalse(report.conforms());
        assertEquals(1, report.failures().size());
        assertTrue(report.render().contains(Clauses.NON_ROOT));
        assertTrue(report.render().contains("USER=<unset>"),
                "the observation, or the operator cannot tell what to change");
        assertTrue(report.render().contains("DOES NOT CONFORM"));
    }
    /**
     * A hostile label cannot forge a report line or hide one.
     *
     * <p>Docker stores {@code ESC}, {@code CR} and {@code LF} in a label verbatim — measured — so
     * without neutralisation an image could print {@code \r\n  PASS  …} into the verified half,
     * or an SGR conceal to hide the {@code DOES NOT CONFORM} line that comes after it. Separating
     * the halves in the data model buys nothing if the image can re-blend them on the screen the
     * operator is told to read.
     */
    @Test
    void aHostileLabelCannotForgeOrHideAReportLine() {
        String hostile = "node\u001b[2J\u001b[H\r\n  PASS  forged-clause\u001b[8m";

        ConformanceReport report = report(
                List.of(ConformanceReport.Verification.failed(Clauses.GIT, "absent")),
                List.of(new ConformanceReport.Declaration(Clauses.TOOLCHAIN, hostile, "needs the repo")));

        String rendered = report.render();
        assertFalse(rendered.contains("\u001b"), "no escape may survive: " + rendered);
        assertFalse(rendered.contains("\r"), "no carriage return may survive");
        assertEquals(0, rendered.lines().filter(line -> line.startsWith("  PASS")).count(),
                "the only verified clause FAILED, so a PASS line could only have come from the "
                        + "label: " + rendered);
        assertTrue(rendered.contains("DOES NOT CONFORM"),
                "and the verdict line must still be reachable");
    }

    /** The same neutralisation on the other image-controlled path: a clause detail. */
    @Test
    void aHostileEntrypointOrUserCannotForgeAReportLine() {
        String hostile = "/bin/sh\r\n  PASS  forged";

        String rendered = report(
                List.of(ConformanceReport.Verification.passed(Clauses.ENTRYPOINT, hostile)),
                List.of()).render();

        assertEquals(1, rendered.lines().filter(line -> line.startsWith("  PASS")).count(), rendered);
    }

    /**
     * A verified list holding a DECLARED clause id is refused at construction.
     *
     * <p>The assurance split is structural — a declaration has nowhere to put a pass/fail — but
     * clause IDENTITY was defence by test only: {@code new Verification(Clauses.TOOLCHAIN, true,
     * …)} compiled, and the constant is public. One check makes both halves structural.
     */
    @Test
    void aDeclaredClauseCannotBeSmuggledIntoTheVerifiedList() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> report(List.of(ConformanceReport.Verification.passed(
                                Clauses.TOOLCHAIN, "the label was present")),
                        List.of()));

        assertTrue(refused.getMessage().contains(Clauses.TOOLCHAIN), refused.getMessage());
    }

    /**
     * An empty verified list is refused, because {@code conforms()} is an allMatch.
     *
     * <p>Vacuously true over nothing, so an empty report would render "CONFORMS: every verified
     * clause passed" having checked none — the shape this repository already paid for once in a
     * contract-snapshot test.
     */
    @Test
    void aReportWithNoVerifiedClauseIsRefusedRatherThanRenderingAsConforming() {
        assertThrows(IllegalArgumentException.class, () -> report(List.of(), List.of()));
    }

    /** A clause that was not checked is a failure in the report and is marked as unchecked. */
    @Test
    void aNotCheckedClauseIsAFailureThatSaysItIsACheckerProblem() {
        ConformanceReport report = report(
                List.of(AgentImageVerifier.unknown(Clauses.GIT, "daemon unreachable")), List.of());

        assertFalse(report.conforms());
        assertTrue(report.anyNotChecked());
        assertTrue(report.render().contains("not necessarily an image one"), report.render());
    }

    /** A passed clause cannot also claim it was not checked. */
    @Test
    void aClauseCannotBothPassAndBeUnchecked() {
        assertThrows(IllegalArgumentException.class,
                () -> new ConformanceReport.Verification(Clauses.GIT, true, true, "impossible"));
    }

    /** A label present but blank is no label; the report must not print an empty claim. */
    @Test
    void aBlankLabelIsTreatedAsAbsent() {
        assertFalse(new ConformanceReport.Declaration(Clauses.HARNESS, "   ", "why").isPresent());
    }
}