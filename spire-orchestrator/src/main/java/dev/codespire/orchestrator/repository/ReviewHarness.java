package dev.codespire.orchestrator.repository;

import dev.codespire.contract.work.PayWith;

/**
 * The harness half of a repository's "Review with" setting (harness reviews, step 5): which harness reviews its pull
 * requests, on which model and thinking level, and how the run pays. A repository has this or an API provider, never
 * both (V109).
 *
 * @param effort the thinking level; null means the model's own default
 * @param payWith {@link PayWith#API_KEY} or {@link PayWith#SUBSCRIPTION}
 */
public record ReviewHarness(String harness, String model, String effort, String payWith) {

    /** Whether the run pays with a signed-in seat rather than an API key. */
    public boolean subscription() {
        return PayWith.SUBSCRIPTION.equals(payWith);
    }
}
