package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.TokenCount;
import dev.codespire.contract.review.TokenType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * What a harness reported, translated into what the ledger prices.
 *
 * <p>This is the seam ADR-023 was written about: four separate, individually defensible places where
 * <em>unknown</em> silently became <em>zero</em>, and a spend cap built on the result installed
 * cleanly and never fired. Every degradation here therefore resolves to a CATEGORY the ledger already
 * understands — an unpriceable call — rather than to a number.
 *
 * <p>The wire carries {@code Map<String, Long>} keyed by the harness tier's own
 * {@code TokenBucket} names, which the ledger's {@code TokenType} mirrors one-to-one; the harness
 * module's own guard fails the build if the two drift. The map is read by NAME rather than by
 * importing that enum, because the orchestrator does not depend on the harness tier.
 */
final class RunTokenUsage {

    private RunTokenUsage() {
    }

    /**
     * Price-ready usage for one run.
     *
     * <p>An unreconciled result carries a single {@link TokenType#TOTAL} line, which the pricer
     * records as UNKNOWN. That is the honest answer for every case below, and it is reached rather
     * than thrown, because by the time this runs the money is spent and the branch may be pushed —
     * a throw here dead-letters the result and loses both.
     */
    static ModelUsage of(RunResult result, String model) {
        Map<String, Long> reported = usageOf(result);
        if (reported == null || reported.isEmpty()) {
            // Null IS unknown on the wire, and it stays unknown here. Building zero-valued counts
            // instead would price the run FREE, which is the confidently understated total this
            // ledger exists to prevent.
            return new ModelUsage(model, List.of(), 0, false);
        }
        List<TokenCount> counts = new ArrayList<>();
        long total = 0;
        boolean trustworthy = true;
        for (Map.Entry<String, Long> entry : reported.entrySet()) {
            long tokens = entry.getValue() == null ? 0L : entry.getValue();
            total += tokens;
            TokenType type = dimensionOf(entry.getKey());
            if (type == null || type == TokenType.TOTAL || !fitsTheLedger(tokens)) {
                // Three different faults, one answer: a bucket the ledger has no dimension for, a
                // harness that could not partition its own usage, and a count outside the ledger's
                // range. In every case the SPLIT cannot be trusted, and applying an INPUT rate to a
                // dimension we misread is how a wrong number gets a confident price.
                trustworthy = false;
                continue;
            }
            counts.add(new TokenCount(type, (int) tokens));
        }
        int reportedTotal = fitsTheLedger(total) ? (int) total : 0;
        return trustworthy
                ? new ModelUsage(model, counts, reportedTotal, true)
                : new ModelUsage(model, List.of(new TokenCount(TokenType.TOTAL, reportedTotal)),
                        reportedTotal, false);
    }

    /** The usage a result carries, or null when it carries none. */
    private static Map<String, Long> usageOf(RunResult result) {
        return switch (result) {
            case RunResult.RunFinished finished -> finished.tokenUsage();
            case RunResult.RunFailed failed -> failed.tokenUsage();
            case RunResult.RunStarted ignored -> null;
        };
    }

    /**
     * Whether a reported count can be recorded at all.
     *
     * <p>The wire carries longs and the ledger's count is an int, so a cast overflows to a negative
     * and {@link TokenCount} then throws — inside the result handler, after the money is spent, on
     * every replay. A negative arriving directly from a buggy vendor proxy is the same case: it is a
     * value from OUTSIDE, so the boundary answers rather than the constructor that exists to catch a
     * caller's own arithmetic bug.
     *
     * <p>Clamping to {@link Integer#MAX_VALUE} was the obvious alternative and is worse: it writes a
     * specific, wrong, PRICED number that nobody can trace back to a real charge.
     */
    private static boolean fitsTheLedger(long tokens) {
        return tokens >= 0 && tokens <= Integer.MAX_VALUE;
    }

    /** The ledger dimension a harness bucket name maps to, or null when there is none. */
    private static TokenType dimensionOf(String bucket) {
        for (TokenType type : TokenType.values()) {
            if (type.name().equals(bucket)) {
                return type;
            }
        }
        return null;
    }
}
