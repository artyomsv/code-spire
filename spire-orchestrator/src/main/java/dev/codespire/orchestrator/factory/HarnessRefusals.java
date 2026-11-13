package dev.codespire.orchestrator.factory;

import dev.codespire.contract.llm.HarnessPolicy;
import dev.codespire.contract.work.PayWith;

/**
 * The harness-policy refusals the factory's doors share (harness reviews 3b): a subscription for a harness that may
 * not use one ({@link HarnessPolicy#SUBSCRIPTION_NOT_ALLOWED}), and a build, a fix or a factory run on a harness that
 * may only review ({@link HarnessPolicy#HARNESS_REVIEWS_ONLY}). Each door refuses in its own shape; the rule and its
 * wording live here once.
 */
final class HarnessRefusals {

    private HarnessRefusals() {
    }

    // NOT ALLOWED — kept for history only. Anthropic's terms forbid a third-party product to route requests
    // through a Claude subscription or to hold claude.ai tokens:
    // https://code.claude.com/docs/en/legal-and-compliance (read 2026-11-08). Claude Code pays with an API key.
    /** Whether a subscription seat is refused for this harness: by name, whatever this deployment configures. */
    static boolean refusesSubscription(String harness) {
        return !HarnessPolicy.allowsSubscription(harness);
    }

    /** The pool's own door: a seat for a harness that may not use one is never stored. */
    static void requireSubscriptionAllowed(String harness) {
        if (refusesSubscription(harness)) throw new IllegalStateException(HarnessPolicy.SUBSCRIPTION_NOT_ALLOWED);
    }

    /**
     * The build setup's payment, normalised, after what the harness may be paid with and used for — and before
     * anything about the model is asked.
     *
     * @throws BuildDefaults.Refused {@code pay_with_invalid}, {@link HarnessPolicy#SUBSCRIPTION_NOT_ALLOWED} or
     *     {@link HarnessPolicy#HARNESS_REVIEWS_ONLY}
     */
    static String buildSetupPayWith(String harness, String requested) {
        String payWith;
        try { payWith = PayWith.normalise(requested); }
        catch (IllegalArgumentException unknown) { throw new BuildDefaults.Refused("pay_with_invalid"); }
        if (payWith.equals(PayWith.SUBSCRIPTION) && refusesSubscription(harness))
            throw new BuildDefaults.Refused(HarnessPolicy.SUBSCRIPTION_NOT_ALLOWED);
        if (!HarnessPolicy.allowsBuilds(harness)) throw new BuildDefaults.Refused(HarnessPolicy.HARNESS_REVIEWS_ONLY);
        return payWith;
    }

    /** The harness a dispatch names, refused with a 400 when it may only review. */
    static String requireBuildHarness(String harness) {
        if (!HarnessPolicy.allowsBuilds(harness)) {
            throw DispatchRequestParser.badRequest(HarnessPolicy.HARNESS_REVIEWS_ONLY + ": the '" + harness
                    + "' harness reviews pull requests only; it cannot run a build, a fix or a factory run");
        }
        return harness;
    }

    /**
     * A work item's build on a harness that may only review. Asked before the step and the parse: the parse would
     * refuse it too, in prose the item cannot show.
     */
    static void refuseReviewOnlyBuild(String harness) {
        if (!HarnessPolicy.allowsBuilds(harness))
            throw new IllegalStateException(HarnessPolicy.HARNESS_REVIEWS_ONLY);
    }

    /**
     * Why {@code SPIRE_FACTORY_FIX_HARNESS} cannot run a fix, or null when it can: unset, or a harness that may only
     * review.
     */
    static String fixHarnessRefusal(String harness) {
        if (harness.isBlank()) {
            return "this deployment has not enabled /fix — an operator must set SPIRE_FACTORY_FIX_HARNESS";
        }
        if (!HarnessPolicy.allowsBuilds(harness)) {
            return "the '" + harness + "' harness reviews pull requests only (" + HarnessPolicy.HARNESS_REVIEWS_ONLY
                    + "), so it cannot run a fix — an operator must set SPIRE_FACTORY_FIX_HARNESS to a harness that builds";
        }
        return null;
    }
}
