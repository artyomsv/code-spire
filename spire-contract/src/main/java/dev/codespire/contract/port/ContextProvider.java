package dev.codespire.contract.port;

import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextRequest;

import java.util.concurrent.CompletionStage;

/**
 * A pluggable context source (Jira, Confluence, rules, RAG, memory). Providers
 * are discovered via CDI; adding one = a new bean, zero core change. Each is
 * time-boxed by the aggregator's completeness/timeout policy (CONTRACT §8).
 * Retrieved content is UNTRUSTED input to the prompt (SECURITY.md).
 */
public interface ContextProvider {

    String source();

    boolean supports(ContextRequest request);

    CompletionStage<ContextContribution> contribute(ContextRequest request);
}
