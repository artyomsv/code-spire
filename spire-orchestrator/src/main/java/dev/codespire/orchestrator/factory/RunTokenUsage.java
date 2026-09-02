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
 * <p>The wire carries {@code Map<String, Long>} keyed by the harness tier's own {@code TokenBucket}
 * names, which the ledger's {@code TokenType} mirrors one-to-one; the harness module's own guard fails
 * the build if the two drift. The map is read by NAME rather than by importing that enum, because the
 * orchestrator does not depend on the harness tier.
 *
 * <p>Four producer faults are handled, and they are not interchangeable:
 * <ul>
 *   <li>a bucket the ledger has no dimension for — its tokens are real and still count toward the
 *       total, but no rate may be applied to a dimension we misread;</li>
 *   <li>a count too large for the ledger's column — it still counts toward the total, which is
 *       never priced, but never gets a per-type count, which is;</li>
 *   <li>a negative count — not a magnitude at all, so it contributes to nothing;</li>
 *   <li>a named bucket with no number — the harness did not measure that dimension, and calling that
 *       zero prices it as genuinely free;</li>
 *   <li>{@code TOTAL} arriving alongside the per-type buckets — the producing enum's own contract
 *       says it is never mixed with them and warns that a consumer summing them all would
 *       double-count the whole run. This is that consumer.</li>
 * </ul>
 */
final class RunTokenUsage {

    private RunTokenUsage() {
    }

    /**
     * Price-ready usage for one run.
     *
     * <p>An unreconciled result carries a single {@link TokenType#TOTAL} line, which the pricer
     * records as UNKNOWN. That is the honest answer for every fault above, and it is reached rather
     * than thrown, because by the time this runs the money is spent and the branch may be pushed — a
     * throw here dead-letters the result and loses both.
     */
    static ModelUsage of(RunResult result, String model) {
        return of(result, model, UNBOUNDED);
    }

    /** No operator ceiling: what the run reported is priced as reported. */
    static final long UNBOUNDED = -1L;

    /**
     * Whether an operator actually set a ceiling.
     *
     * <p>Any non-positive value means unlimited, not "refuse everything". Zero is the important
     * half: it is what an uninjected {@code long} field holds, so a caller constructed outside CDI
     * would otherwise unprice every run in the deployment — a hardening control turning itself
     * into the outage it exists to prevent. It is also not a setting anyone can have meant, since
     * a ceiling of zero tokens makes every run unpriceable by definition.
     */
    private static boolean bounded(long ceiling) {
        return ceiling > 0;
    }

    /**
     * Price-ready usage, refusing to price a report above {@code ceiling}.
     *
     * <p>The agent reports its own usage — the harness parses it from the container's stdout, and
     * the agent runs shell at full access in there by design. That was harmless while usage was
     * telemetry. It stopped being harmless the moment a run's spend started moving the
     * deployment-wide cap, because a fabricated multi-billion-token line prices high enough for
     * {@code SpendGate} to refuse every paid call until the window drains — taking out the
     * reviewer as well as the factory, from one run.
     *
     * <p>Above the ceiling the whole call degrades to UNKNOWN rather than being priced. It is not
     * discarded: the run still leaves a row and still counts on the call axis, so the mitigation
     * cannot itself become a way to spend unseen.
     *
     * <p><b>Unset means unlimited</b>, matching every other cap in ADR-025. A plausible default
     * would be a number this code invented about somebody else's models, and the wrong one would
     * silently unprice honest runs.
     */
    static ModelUsage of(RunResult result, String model, long ceiling) {
        Map<String, Long> reported = usageOf(result);
        if (reported == null || reported.isEmpty()) {
            // Null IS unknown on the wire, and it stays unknown here. Building zero-valued counts
            // instead would price the run FREE, which is the confidently understated total this
            // ledger exists to prevent.
            return new ModelUsage(model, List.of(), 0, false);
        }
        Partition partition = partition(reported);
        int total = partition.reportedTotal();
        boolean withinCeiling = !bounded(ceiling)
                || Math.max(partition.summed(), partition.declaredTotal()) <= ceiling;
        return partition.trustworthy() && withinCeiling
                ? new ModelUsage(model, partition.counts(), total, true)
                : new ModelUsage(model, List.of(new TokenCount(TokenType.TOTAL, total)), total, false);
    }

    /**
     * The per-type counts, whether the split can be trusted, and the run's total.
     *
     * @param declaredTotal what the producer itself called the total, when it sent one
     */
    private record Partition(List<TokenCount> counts, boolean trustworthy, long summed, long declaredTotal) {

        /**
         * The reportable total, saturating rather than falling back to zero.
         *
         * <p>A degraded line is written as {@code ChargeLine.unknown}, whose cost is NULL by the
         * ledger's own CHECK, so this number is never priced — which is why saturating is safe here
         * and is NOT safe for a per-type count, where the same value would be multiplied by a rate.
         * Zero was the first answer and is the one direction ADR-023 forbids: it makes a run of more
         * than two billion tokens and a run nobody measured write byte-identical rows.
         */
        int reportedTotal() {
            long value = Math.max(summed, declaredTotal);
            return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
        }
    }

    private static Partition partition(Map<String, Long> reported) {
        List<TokenCount> counts = new ArrayList<>();
        long summed = 0;
        long declaredTotal = 0;
        boolean trustworthy = true;
        for (Map.Entry<String, Long> entry : reported.entrySet()) {
            Long tokens = entry.getValue();
            TokenType type = dimensionOf(entry.getKey());
            if (tokens == null || tokens < 0) {
                // Not a magnitude at all: a named bucket the harness did not measure, or a count
                // a buggy proxy reported as negative. Neither can contribute to a total.
                trustworthy = false;
                continue;
            }
            if (tokens > Integer.MAX_VALUE) {
                // Real tokens the column cannot hold. Saturated INTO the total rather than
                // dropped, so the run still reads as enormous instead of as unmeasured — but
                // never given a per-type count, where the same number would be multiplied by a
                // rate. That asymmetry is the whole point: an unpriced line may saturate, a
                // priced one may not.
                trustworthy = false;
                summed = plus(summed, Integer.MAX_VALUE);
                continue;
            }
            if (type == TokenType.TOTAL) {
                // A producer sending TOTAL has told us it could not partition its usage, so its value
                // IS the total rather than a term in one. Summing it alongside the per-type buckets is
                // the double-count the producing enum's javadoc warns about by name.
                trustworthy = false;
                declaredTotal = Math.max(declaredTotal, tokens);
                continue;
            }
            // An unrecognised bucket's tokens are real, so they still count toward the total — losing
            // them would make the run look smaller than it was. What is lost is the SPLIT: no rate may
            // be applied to a dimension we misread.
            summed = plus(summed, tokens);
            if (type == null) {
                trustworthy = false;
                continue;
            }
            counts.add(new TokenCount(type, tokens.intValue()));
        }
        if (summed > Integer.MAX_VALUE) {
            // ModelUsage documents reconciled as "counts sums to reportedTotal". A total the column
            // cannot hold would leave that a lie on the trustworthy path, which a future reader would
            // believe.
            trustworthy = false;
        }
        return new Partition(counts, trustworthy, summed, declaredTotal);
    }

    /**
     * Saturating addition.
     *
     * <p>Plain {@code +} let a crafted pair of near-maximum values wrap to a small positive
     * number that then passed the range check and was written as the run's total — a specific,
     * confident, wrong figure, which is worse than an obviously saturated one.
     */
    private static long plus(long running, long tokens) {
        long sum = running + tokens;
        return sum < running ? Long.MAX_VALUE : sum;
    }

    /** The usage a result carries, or null when it carries none. */
    private static Map<String, Long> usageOf(RunResult result) {
        return switch (result) {
            case RunResult.RunFinished finished -> finished.tokenUsage();
            case RunResult.RunFailed failed -> failed.tokenUsage();
            case RunResult.RunStarted ignored -> null;
        };
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
