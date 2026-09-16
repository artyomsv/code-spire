package dev.codespire.orchestrator.llm;

import java.util.List;
import java.util.Map;

/**
 * Create/update payload for a catalog model.
 *
 * <p>{@code pricingMode} is "METERED" or "UNMETERED". Under METERED, {@code rates} maps a
 * {@code TokenType} name to millicents per 1,000,000 tokens and must contain a rate greater than zero
 * for at least INPUT and OUTPUT — the two dimensions every vendor reports on every call. The optional
 * dimensions (CACHED_INPUT, CACHE_WRITE, REASONING) may be omitted, because a model that does not bill
 * for them cannot be asked to price them.
 *
 * <p>Under UNMETERED, {@code rates} must be empty: the cost is an asserted zero.
 *
 * <p>{@code notBilled} names the optional dimensions the operator asserts this vendor does not charge
 * for. It is a third state beside a rate and an absent entry: a rate is a price somebody read off a
 * vendor's page, an assertion is a person saying "nothing is charged for this", and an absent entry is
 * nobody having said — which keeps a call reporting it UNPRICED rather than free. INPUT and OUTPUT
 * cannot be asserted: a model that charges for neither is UNMETERED, which already says exactly that.
 */
public record LlmModelInput(
        String type,
        String name,
        String label,
        String pricingMode,
        Map<String, Long> rates,
        String outputTokenParam,
        Boolean supportsTemperature,
        String reasoningEffort,
        Map<String, Object> extraParams,
        Boolean enabled,
        List<String> notBilled) {
}
