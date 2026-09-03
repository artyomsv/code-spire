package dev.codespire.agentimage;

import org.junit.jupiter.api.Test;

import java.lang.reflect.RecordComponent;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
        String rendered = report(List.of(),
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
}
