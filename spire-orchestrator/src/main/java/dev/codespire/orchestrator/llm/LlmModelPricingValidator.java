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

    private LlmModelPricingValidator() {
    }

    /** What a save should persist once validation has passed. */
    record Validated(PricingMode mode, Map<TokenType, Long> rates) {
    }

    /** @throws IllegalArgumentException if the mode or rates are not a saveable combination */
    static Validated validate(LlmModelInput in) {
        PricingMode mode = parseMode(in.pricingMode());
        Map<String, Long> rawRates = in.rates() == null ? Map.of() : in.rates();
        if (mode == PricingMode.UNMETERED) {
            if (!rawRates.isEmpty()) {
                throw new IllegalArgumentException(
                        "An UNMETERED model asserts a zero cost, so it must carry no rates");
            }
            return new Validated(mode, Map.of());
        }
        requireEveryMandatoryRate(rawRates);
        rawRates.forEach((type, rate) -> {
            if (rate == null || rate <= 0) {
                throw new IllegalArgumentException("Rate for " + type + " must be above zero");
            }
        });
        return new Validated(mode, parseRates(rawRates));
    }

    private static void requireEveryMandatoryRate(Map<String, Long> rawRates) {
        for (TokenType required : REQUIRED_RATES) {
            Long rate = rawRates.get(required.name());
            if (rate == null || rate <= 0) {
                throw new IllegalArgumentException("A METERED model needs a rate above zero for "
                        + required.name() + ". If this model is self-hosted and costs nothing to call,"
                        + " set its pricing mode to UNMETERED instead of entering a zero — a zero rate"
                        + " and an unentered rate must stay distinguishable.");
            }
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

    private static Map<TokenType, Long> parseRates(Map<String, Long> rates) {
        Map<TokenType, Long> parsed = new EnumMap<>(TokenType.class);
        rates.forEach((key, rate) -> parsed.put(parseRateType(key), rate));
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
