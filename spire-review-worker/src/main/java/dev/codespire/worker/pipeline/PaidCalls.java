package dev.codespire.worker.pipeline;

import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.port.LlmProvider;
import dev.codespire.worker.adapters.LlmFailures;

/**
 * One paid model call of a review, with the rule a large prompt adds (review input limit design, 2026-11-26): a
 * timeout of a call whose diff budget was above today's fixed one is not retried. The call is non-streaming, so a
 * retry sends the whole prompt again and may be billed each time; a small call's timeout stays retryable.
 */
final class PaidCalls {

    private PaidCalls() {
    }

    static Completion complete(LlmProvider provider, Prompt prompt, ModelParams params, boolean largePrompt) {
        try {
            return provider.complete(prompt, params).toCompletableFuture().join();
        } catch (RuntimeException e) {
            Throwable cause = WorkerFailures.unwrap(e);
            if (largePrompt && LlmFailures.isTimeout(cause)) {
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
