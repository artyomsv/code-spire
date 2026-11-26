package dev.codespire.worker.pipeline;

import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.port.LlmProvider;
import dev.codespire.llm.PromptSize;
import dev.codespire.worker.adapters.LlmFailures;

/**
 * One paid model call of a review, with the rule a large prompt adds (review input limit design, 2026-11-26): a
 * timeout of a call that sent more diff than today's fixed budget is not retried. The call is non-streaming, so a
 * retry sends the whole prompt again and may be billed each time; a small call's timeout stays retryable.
 *
 * <p>This is the only retry decision for a review's model call. The client library makes none of its own
 * ({@code LangChain4jLlmProvider.SDK_MAX_RETRIES}); a retryable failure reaches the orchestrator, whose retry budget
 * runs the review again.
 */
final class PaidCalls {

    private PaidCalls() {
    }

    /** What one call sends: the prompt, and whether its diff was above today's budget. */
    record Call(Prompt prompt, PromptSize size) {
    }

    static Completion complete(LlmProvider provider, ModelParams params, Call call) {
        try {
            return provider.complete(call.prompt(), params).toCompletableFuture().join();
        } catch (RuntimeException e) {
            Throwable cause = WorkerFailures.unwrap(e);
            if (call.size() == PromptSize.LARGE && LlmFailures.isTimeout(cause)) {
                throw new LargePromptTimedOut(cause);
            }
            throw e;
        }
    }

    /**
     * A terminal failure. The timeout is attached as suppressed, not as the cause: {@link WorkerFailures#isRetryable}
     * walks the cause chain, and a timeout there would make it retryable again.
     */
    static final class LargePromptTimedOut extends RuntimeException {

        LargePromptTimedOut(Throwable timeout) {
            super("The model call timed out on a large prompt (its diff was above the default review budget). "
                    + "It was not retried, because every attempt sends the whole prompt again and may be billed. "
                    + "Set a lower Review diff limit in Settings → General, or raise SPIRE_LLM_TIMEOUT_SECONDS.");
            addSuppressed(timeout);
        }
    }
}
