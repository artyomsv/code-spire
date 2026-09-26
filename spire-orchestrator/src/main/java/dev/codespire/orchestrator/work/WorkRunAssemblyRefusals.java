package dev.codespire.orchestrator.work;

import dev.codespire.contract.review.TokenType;
import java.util.Set;

/**
 * The refusal a work item is stopped with when its build could not be assembled.
 *
 * <p>Every cause used to collapse into {@code build_configuration_unavailable}: a missing factory
 * account, an exhausted credential pool, a deployment over its cap and a model with no rate for a type
 * the harness reports all read the same on the item's page. An approved plan would stop with one word
 * and nothing to act on. The assembly already names the rule that refused, so a name this screen
 * knows is kept, and anything else stays the old word rather than putting an exception's prose — which
 * can carry a forge's response body — on a page.
 */
final class WorkRunAssemblyRefusals {

    private static final String UNNAMED = "build_configuration_unavailable";

    /** Reasons the dashboard has a sentence for, and which carry no payload at all. */
    private static final Set<String> NAMED = Set.of(
            "factory_account_unavailable", "harness_credential_unavailable", "deployment_spend_cap_reached",
            "model_pricing_unavailable", "model_disabled", "catalogue_unavailable",
            "model_not_run_by_harness", "effort_not_offered", "effort_unverifiable",
            "subscription_unavailable", "subscription_unreadable");

    /** The one reason that carries a payload: "model_pricing_incomplete:CACHED_INPUT,REASONING". */
    private static final String PRICING = "model_pricing_incomplete";

    private WorkRunAssemblyRefusals() {}

    /**
     * Matching is EXACT, and the one payload is parsed rather than trusted. A prefix match would let
     * any text after the first colon through into a durable item reason and onto a page — today's
     * producers emit fixed literals, but "today's producers are safe" is not a boundary.
     */
    static String reasonOf(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return UNNAMED;
        if (NAMED.contains(message)) return message;
        if (!message.startsWith(PRICING + ":")) return UNNAMED;
        String[] types = message.substring(PRICING.length() + 1).split(",", -1);
        if (types.length == 0) return UNNAMED;
        for (String type : types) {
            try {
                if (TokenType.valueOf(type) == TokenType.TOTAL) return UNNAMED;
            } catch (IllegalArgumentException notAType) {
                return UNNAMED;
            }
        }
        return message;
    }
}
