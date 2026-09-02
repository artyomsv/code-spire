package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.TokenType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The translation from what a harness reported to what the ledger prices.
 *
 * <p>This sits exactly where ADR-023's failures lived: four separate, individually defensible places
 * where <em>unknown</em> silently became <em>zero</em>. Every case below is one of those doors held
 * shut, so each asserts a CATEGORY rather than a number.
 */
class RunTokenUsageTest {

    private static RunResult.RunFinished finished(Map<String, Long> usage) {
        return new RunResult.RunFinished("run::github:TEST-acme/app:s:1", "refs/heads/spire/s",
                List.of(), List.of(), usage, false);
    }

    @Test
    void everyBucketTheHarnessReportedBecomesItsOwnPricedDimension() {
        ModelUsage usage = RunTokenUsage.of(finished(Map.of(
                "INPUT", 1200L, "CACHED_INPUT", 800L, "OUTPUT", 340L, "REASONING", 60L)), "TEST-model");

        assertEquals("TEST-model", usage.model());
        assertTrue(usage.reconciled(), "a per-bucket report IS the split; nothing has to be inferred");
        assertEquals(1200, usage.tokensOf(TokenType.INPUT));
        assertEquals(800, usage.tokensOf(TokenType.CACHED_INPUT));
        assertEquals(340, usage.tokensOf(TokenType.OUTPUT));
        assertEquals(60, usage.tokensOf(TokenType.REASONING));
        assertEquals(2400, usage.reportedTotal(), "the total is the partition's own sum, not a second claim");
    }

    @Test
    void aHarnessThatReportedNothingIsUnknownNeverZero() {
        // The wire already says null IS unknown. If that arrived here as an empty ModelUsage the
        // pricer would write one UNKNOWN TOTAL line, which is right — but a mapper that instead
        // built zero-valued counts would write a priced zero, and a run reported free is exactly
        // the confidently understated total this ledger exists to prevent.
        ModelUsage usage = RunTokenUsage.of(finished(null), "TEST-model");

        assertTrue(usage.counts().isEmpty(),
                "no counts means the pricer records the call as UNKNOWN; a zero count would price it free");
        assertEquals(0, usage.reportedTotal());
    }

    @Test
    void aCountTooLargeForTheLedgerIsUnknownNotAWrappedNegative() {
        // The wire carries longs and the ledger's count is an int. A cast overflows to a negative,
        // TokenCount then throws, and the throw lands inside the result handler — paid, pushed, and
        // permanently dead-lettered on every replay. That is the negative-token defect this project
        // already paid for once, arriving from the other end.
        //
        // The value is never given a per-type COUNT, where clamping would write a specific wrong
        // number that a rate then multiplies. It does saturate into the run's reported TOTAL,
        // which is only ever written on an unpriced line -- so the run reads as enormous rather
        // than as unmeasured, and nothing prices it.
        ModelUsage usage = RunTokenUsage.of(
                finished(Map.of("INPUT", (long) Integer.MAX_VALUE + 1L)), "TEST-model");

        assertFalse(usage.reconciled(), "an unreconciled call is priced UNKNOWN, not metered");
        assertTrue(usage.counts().stream().allMatch(c -> c.type() == TokenType.TOTAL),
                "the whole call degrades to one unpriceable line rather than losing the oversized bucket");
        assertEquals(Integer.MAX_VALUE, usage.reportedTotal(),
                "and it saturates rather than reading as zero: a degraded line is never priced, so"
                        + " the only thing zero would achieve is making a two-billion-token run and a"
                        + " run nobody measured write byte-identical rows");
    }

    @Test
    void aNegativeCountIsUnknownRatherThanRefusedAfterTheMoneyIsSpent() {
        // A buggy proxy reporting -1 must not dead-letter a run that has already been paid for and
        // whose branch is on the remote. Refusing it in TokenCount's constructor is right for a
        // caller with an arithmetic bug; here the value comes from OUTSIDE, so the boundary answers.
        ModelUsage usage = RunTokenUsage.of(finished(Map.of("OUTPUT", -1L)), "TEST-model");

        assertFalse(usage.reconciled());
        assertTrue(usage.counts().stream().allMatch(c -> c.type() == TokenType.TOTAL));
    }

    @Test
    void aBucketNameTheLedgerDoesNotKnowDegradesTheCallRatherThanVanishing() {
        // A harness gaining a bucket the ledger has no dimension for must not have those tokens
        // quietly dropped: the remaining lines would then price as if the call were smaller than it
        // was. TokenBucketMatchesLedgerDimensionsTest keeps the two enums aligned, so this is the
        // behaviour when that guard is bypassed by a producer sending something else entirely.
        ModelUsage usage = RunTokenUsage.of(
                finished(Map.of("INPUT", 100L, "SOMETHING_NEW", 50L)), "TEST-model");

        assertFalse(usage.reconciled(), "an unrecognised dimension makes the split untrustworthy");
        assertEquals(150, usage.reportedTotal(), "and its tokens still count toward the reported total");
    }

    @Test
    void aTotalOnlyReportIsCarriedAsTotalAndNeverMetered() {
        // A harness that could not partition its own usage reports TOTAL. Pricing that per-type
        // would apply an INPUT rate to output tokens.
        ModelUsage usage = RunTokenUsage.of(finished(Map.of("TOTAL", 900L)), "TEST-model");

        assertFalse(usage.reconciled());
        assertEquals(900, usage.reportedTotal());
    }

    @Test
    void aMixedTotalAndPerTypeReportIsNotDoubleCounted() {
        // The producing enum's own javadoc warns that TOTAL is never mixed with the per-type
        // buckets and that a consumer summing them all would double-count the whole run. This is
        // that consumer. Latent today -- the one shipped harness never mixes them -- but the class
        // guards three other producer faults and skipped the one the producer explicitly names,
        // and a second harness arm is the entire point of the SPI.
        ModelUsage usage = RunTokenUsage.of(
                finished(Map.of("INPUT", 1000L, "OUTPUT", 500L, "TOTAL", 1500L)), "TEST-model");

        assertFalse(usage.reconciled(), "a mixed report means the split cannot be trusted");
        assertEquals(1500, usage.reportedTotal(),
                "the reported total is the producer's own TOTAL, not the sum of everything it sent");
    }

    @Test
    void aSumThatWouldWrapDoesNotPassAsASmallNumber() {
        // Each value is inside the ledger's range on its own, so both survive the per-value check
        // and the SUM is what overflows. Accumulating before checking let a crafted pair wrap to a
        // small positive number that then passed the range check and was written as the total.
        ModelUsage usage = RunTokenUsage.of(finished(Map.of(
                "INPUT", (long) Integer.MAX_VALUE, "OUTPUT", (long) Integer.MAX_VALUE)), "TEST-model");

        assertFalse(usage.reconciled(),
                "ModelUsage documents reconciled as 'counts sums to reportedTotal'; a total the"
                        + " column cannot hold would leave that a lie on the trustworthy path");
        assertEquals(Integer.MAX_VALUE, usage.reportedTotal());
    }

    @Test
    void aFailedRunsUsageIsMappedAtThisBoundaryToo() {
        // RunFailed carrying usage is new, and every other case here goes through RunFinished --
        // so the branch that reads a failure's usage was covered only indirectly, one layer up.
        ModelUsage usage = RunTokenUsage.of(new RunResult.RunFailed(
                "run::github:TEST-acme/app:s:1", "AGENT_FAILED", "exit 2", false,
                Map.of("INPUT", 700L, "OUTPUT", 90L)), "TEST-model");

        assertTrue(usage.reconciled());
        assertEquals(700, usage.tokensOf(TokenType.INPUT));
        assertEquals(790, usage.reportedTotal());
    }

    @Test
    void aReportAboveTheOperatorsCeilingIsNotPriced() {
        // The agent reports its own usage, parsed from a container it runs shell in at full access.
        // That was harmless while usage was telemetry; it stopped being harmless when a run's spend
        // started moving the deployment-wide cap, because one fabricated multi-billion-token line
        // prices high enough to refuse every paid call until the window drains -- taking out the
        // reviewer as well as the factory.
        ModelUsage usage = RunTokenUsage.of(finished(Map.of("INPUT", 900L, "OUTPUT", 200L)),
                "TEST-model", 1000L);

        assertFalse(usage.reconciled(), "above the ceiling the call is recorded UNKNOWN, not priced");
        assertEquals(1100, usage.reportedTotal(),
                "and it is not discarded: the run still leaves a row and still counts on the call"
                        + " axis, so the mitigation cannot itself become a way to spend unseen");
    }

    @Test
    void aReportWithinTheCeilingIsPricedNormally() {
        // Without this the ceiling could refuse everything and every test above would still pass,
        // because they run unbounded.
        ModelUsage usage = RunTokenUsage.of(finished(Map.of("INPUT", 900L, "OUTPUT", 200L)),
                "TEST-model", 5000L);

        assertTrue(usage.reconciled());
        assertEquals(900, usage.tokensOf(TokenType.INPUT));
    }

    @Test
    void noCeilingMeansUnlimited() {
        // Matching every other cap in ADR-025. A plausible default would be a number this code
        // invented about somebody else's models, and the wrong one silently unprices honest runs.
        ModelUsage usage = RunTokenUsage.of(finished(Map.of("INPUT", 900_000_000L)),
                "TEST-model", RunTokenUsage.UNBOUNDED);

        assertTrue(usage.reconciled());
    }

    @Test
    void aCeilingOfZeroMeansUnlimitedNotRefuseEverything() {
        // Zero is what an uninjected long field holds, so treating it as a real ceiling would let a
        // caller constructed outside CDI unprice every run in the deployment -- a hardening control
        // turning into the outage it exists to prevent. It is also not a setting anyone can have
        // meant: a ceiling of zero tokens makes every run unpriceable by definition.
        ModelUsage usage = RunTokenUsage.of(finished(Map.of("INPUT", 900L)), "TEST-model", 0L);

        assertTrue(usage.reconciled());
        assertEquals(900, usage.tokensOf(TokenType.INPUT));
    }
}
