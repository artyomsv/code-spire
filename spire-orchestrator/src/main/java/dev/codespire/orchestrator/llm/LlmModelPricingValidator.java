package dev.codespire.orchestrator.llm;

import dev.codespire.contract.review.TokenType;

import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Validates and normalizes a catalog model's pricing before it is written. Split out of
 * {@link LlmModelRegistry} purely for size — this is pure validation with no I/O, so it lives as
 * static methods rather than its own CDI bean.
 */
final class LlmModelPricingValidator {

    /**
     * Mandatory because every vendor reports these two on every call; the rest are model-specific.
     * Shared with {@link LlmModelPricer}, which needs the same set to decide {@code isPriceable}.
     */
    static final List<TokenType> REQUIRED_RATES = List.of(TokenType.INPUT, TokenType.OUTPUT);

    /**
     * The largest saveable rate: 1e9 millicents = <b>$10,000 per million tokens</b>, some fifty times
     * the priciest model published anywhere. A typo bound, not a pricing policy — a real price must
     * never hit it.
     *
     * <p>Bounded because {@code ChargeLine.metered} computes {@code tokens × rate} in a {@code long} of
     * millicents. Above roughly 9.2e12 that product wraps and the ledger stores a NEGATIVE cost, which
     * subtracts from the review's total and from any deployment-wide sum — silently, and with no
     * attention row, unlike an unpriced call. This value is chosen so even {@code Integer.MAX_VALUE}
     * tokens cannot overflow it (2.1e9 × 1e9 = 2.1e18, against a ceiling of 9.2e18), which means the
     * bound holds without knowing anything about the call it will price.
     */
    static final long MAX_RATE_MILLICENTS_PER_MILLION = 1_000_000_000L;

    private LlmModelPricingValidator() {
    }

    /** What a save should persist once validation has passed. */
    record Validated(PricingMode mode, Map<TokenType, ModelRate> rates) {
    }

    /** @throws IllegalArgumentException if the mode or rates are not a saveable combination */
    static Validated validate(LlmModelInput in) {
        PricingMode mode = parseMode(in.pricingMode());
        Map<String, Long> rawRates = in.rates() == null ? Map.of() : in.rates();
        List<String> notBilled = in.notBilled() == null ? List.of() : in.notBilled();
        if (mode == PricingMode.UNMETERED) {
            if (!rawRates.isEmpty() || !notBilled.isEmpty()) {
                throw new IllegalArgumentException(
                        "An UNMETERED model asserts a zero cost for the whole model, so it must carry"
                        + " no rates and no per-type assertions");
            }
            return new Validated(mode, Map.of());
        }
        rawRates.forEach(LlmModelPricingValidator::requireRateInRange);
        Map<TokenType, ModelRate> parsed = parseRates(rawRates);
        for (String raw : notBilled) {
            TokenType type = parseRateType(raw);
            if (parsed.containsKey(type)) {
                throw new IllegalArgumentException(type.name() + " has both a rate and a"
                        + " not-billed assertion. Keep the one that is true of this vendor.");
            }
            parsed.put(type, ModelRate.notBilled());
        }
        // INPUT and OUTPUT must be SAID, one way or the other. A vendor that bills nothing for one of
        // them is a real schedule — the first draft of this rule refused it and pointed at UNMETERED,
        // which asserts zero for the WHOLE model and would have erased the other dimension's real
        // charges. What must not happen is silence: an unsaid mandatory type prices a call as unknown.
        for (TokenType required : REQUIRED_RATES) {
            if (!parsed.containsKey(required)) {
                throw new IllegalArgumentException("A METERED model needs a rate above zero for "
                        + required.name() + ", or an explicit statement that this vendor does not bill it."
                        + " An unentered rate is not a zero: a call reporting it cannot be priced.");
            }
        }
        return new Validated(mode, parsed);
    }

    /**
     * Both ends of the range. The upper end is the half that PREVENTS a wrapped cost: the arithmetic
     * downstream can refuse to compute one, but refusing at charge time only turns a review's spend into
     * an unpriced line, after the money is gone. Rejecting the rate at the save is what stops it.
     */
    private static void requireRateInRange(String type, Long rate) {
        if (rate == null || rate <= 0) {
            // A zero is never a price. It is either an assertion that this vendor does not bill the type —
            // which is stored as such, not as a number — or, for a model that costs nothing at all,
            // UNMETERED for the whole model. Coercing it into a rate is what made an unpriced call read
            // as free.
            throw new IllegalArgumentException("Rate for " + type + " must be above zero. If this vendor"
                    + " does not bill " + type + ", mark it as not billed instead of entering a zero; if"
                    + " the model costs nothing to call at all, set its pricing mode to UNMETERED.");
        }
        if (rate > MAX_RATE_MILLICENTS_PER_MILLION) {
            throw new IllegalArgumentException("Rate for " + type + " is implausibly large ("
                    + rate + " millicents per million tokens = $" + rate / 100_000L
                    + " per million tokens). The maximum is " + MAX_RATE_MILLICENTS_PER_MILLION
                    + " ($" + MAX_RATE_MILLICENTS_PER_MILLION / 100_000L + " per million tokens);"
                    + " above that a call's cost can no longer be computed. Check the unit: rates are"
                    + " millicents per MILLION tokens, so $2.50 per million is 250000.");
        }
    }

    /** UNKNOWN is a runtime outcome, never an operator's choice, so it is not accepted here. */
    private static PricingMode parseMode(String raw) {
        PricingMode mode = raw == null ? null : switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "METERED" -> PricingMode.METERED;
            case "UNMETERED" -> PricingMode.UNMETERED;
            default -> null;
        };
        if (mode == null) {
            throw new IllegalArgumentException("pricingMode must be METERED or UNMETERED");
        }
        return mode;
    }

    private static Map<TokenType, ModelRate> parseRates(Map<String, Long> rates) {
        Map<TokenType, ModelRate> parsed = new EnumMap<>(TokenType.class);
        rates.forEach((key, rate) -> parsed.put(parseRateType(key), ModelRate.rated(rate)));
        return parsed;
    }

    private static TokenType parseRateType(String key) {
        TokenType type;
        try {
            type = TokenType.valueOf(key);
        } catch (IllegalArgumentException unknown) {
            throw new IllegalArgumentException("Unknown token type '" + key
                    + "' — expected one of INPUT, CACHED_INPUT, CACHE_WRITE, OUTPUT, REASONING");
        }
        if (type == TokenType.TOTAL) {
            throw new IllegalArgumentException("TOTAL has no per-call rate — it represents an "
                    + "unreconciled call's whole token count, which cannot be metered");
        }
        return type;
    }
}
