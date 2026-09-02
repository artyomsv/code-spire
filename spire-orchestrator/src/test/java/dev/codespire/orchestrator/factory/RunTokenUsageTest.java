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
        // Clamping to Integer.MAX_VALUE was the obvious alternative and is worse: it writes a
        // specific, wrong, PRICED number that nobody can trace to a real charge.
        ModelUsage usage = RunTokenUsage.of(
                finished(Map.of("INPUT", (long) Integer.MAX_VALUE + 1L)), "TEST-model");

        assertFalse(usage.reconciled(), "an unreconciled call is priced UNKNOWN, not metered");
        assertTrue(usage.counts().stream().allMatch(c -> c.type() == TokenType.TOTAL),
                "the whole call degrades to one unpriceable line rather than losing the oversized bucket");
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
}
