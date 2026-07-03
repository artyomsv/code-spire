package dev.codespire.contract.port;

import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.llm.Prompt;

import java.util.concurrent.CompletionStage;

/**
 * LLM adapter. NO default provider — chosen at configuration time, fail-fast
 * if unset (ADR-001). Default implementation is LangChain4j, swappable.
 */
public interface LlmProvider {

    String id();

    CompletionStage<Completion> complete(Prompt prompt, ModelParams params);
}
