package dev.codespire.orchestrator.work;

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

    /** Reasons the dashboard has a sentence for, so only these are passed through. */
    private static final Set<String> NAMED = Set.of(
            "factory_account_unavailable", "harness_credential_unavailable", "deployment_spend_cap_reached",
            "model_pricing_unavailable", "model_pricing_incomplete");

    private WorkRunAssemblyRefusals() {}

    static String reasonOf(RuntimeException failure) {
        String message = failure.getMessage();
        if (message == null || message.isBlank()) return UNNAMED;
        // "model_pricing_incomplete:CACHED_INPUT,REASONING" — the detail rides with the reason so the
        // page can say which rates are missing without a second lookup.
        String head = message.contains(":") ? message.substring(0, message.indexOf(':')) : message;
        return NAMED.contains(head) ? message : UNNAMED;
    }
}
