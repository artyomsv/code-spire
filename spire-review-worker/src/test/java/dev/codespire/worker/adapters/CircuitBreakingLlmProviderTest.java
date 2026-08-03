package dev.codespire.worker.adapters;

import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.port.LlmProvider;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.worker.adapters.ProviderCircuits.CircuitOpenException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The breaker on the paid call path. Its whole risk is looking installed while doing nothing, so
 * these pin the two ways that happens: not observing a failure at all, and counting a failure that
 * was really an answer.
 */
class CircuitBreakingLlmProviderTest {

    private static final String HOST = "api.example.invalid";
    private static final Prompt PROMPT = new Prompt("system", "user");
    private static final ModelParams PARAMS = new ModelParams("test-model", 0.2, 256, null);

    private final AtomicLong now = new AtomicLong();
    private final ProviderCircuits circuits = new ProviderCircuits(now::get);
    private final AtomicInteger calls = new AtomicInteger();

    /** A definite answer: the key was rejected. The provider is up and told us something true. */
    private static final class KeyRejected extends RuntimeException {
    }

    /**
     * Mirrors the real provider, which catches its own RuntimeException to implement the
     * retry-without-temperature fallback and therefore reports failure as a FAILED FUTURE rather
     * than by throwing.
     */
    private LlmProvider failingWith(RuntimeException failure) {
        return provider(() -> CompletableFuture.failedFuture(failure));
    }

    private LlmProvider provider(java.util.function.Supplier<CompletionStage<Completion>> body) {
        return new LlmProvider() {
            @Override
            public String id() {
                return "test-provider";
            }

            @Override
            public CompletionStage<Completion> complete(Prompt prompt, ModelParams params) {
                calls.incrementAndGet();
                return body.get();
            }
        };
    }

    private CircuitBreakingLlmProvider guard(LlmProvider delegate) {
        return new CircuitBreakingLlmProvider(delegate, HOST, circuits);
    }

    /** Drive the delegate to failure the given number of times, ignoring each failure. */
    private void failTimes(CircuitBreakingLlmProvider provider, int times) {
        for (int i = 0; i < times; i++) {
            assertThrows(CompletionException.class,
                    () -> provider.complete(PROMPT, PARAMS).toCompletableFuture().join());
        }
    }

    /**
     * The trap this class exists to avoid. The delegate never throws — it returns a failed future —
     * so a breaker wrapped naively around the call records every outage as a success and never
     * opens, while looking perfectly installed.
     */
    @Test
    void aFailureDeliveredAsAFailedFutureStillOpensTheCircuit() {
        CircuitBreakingLlmProvider provider = guard(failingWith(new UncheckedIOException(new IOException("down"))));

        failTimes(provider, ProviderCircuits.FAILURE_THRESHOLD);
        int callsBefore = calls.get();

        CompletionException refused = assertThrows(CompletionException.class,
                () -> provider.complete(PROMPT, PARAMS).toCompletableFuture().join());

        assertInstanceOf(CircuitOpenException.class, refused.getCause());
        assertEquals(callsBefore, calls.get(), "an open circuit must not reach the provider at all");
    }

    /**
     * A rejected key is not illness. Counting it would let one bad credential pause every review on
     * the host — and unlike an outage, no amount of waiting fixes it.
     */
    @Test
    void aRejectedKeyNeverOpensTheCircuit() {
        CircuitBreakingLlmProvider provider = guard(failingWith(new KeyRejected()));

        failTimes(provider, ProviderCircuits.FAILURE_THRESHOLD * 2);

        CompletionException stillCalling = assertThrows(CompletionException.class,
                () -> provider.complete(PROMPT, PARAMS).toCompletableFuture().join());
        assertInstanceOf(KeyRejected.class, stillCalling.getCause());
        assertEquals(ProviderCircuits.FAILURE_THRESHOLD * 2 + 1, calls.get());
    }

    /**
     * The provider's own transient hierarchy, recognized by name because the worker never compiles
     * against LangChain4j. Constructed reflectively for that reason.
     */
    @Test
    void aProviderRateLimitOpensTheCircuit() throws Exception {
        RuntimeException rateLimited = (RuntimeException) Class
                .forName("dev.langchain4j.exception.RateLimitException")
                .getConstructor(String.class)
                .newInstance("slow down");
        CircuitBreakingLlmProvider provider = guard(failingWith(rateLimited));

        failTimes(provider, ProviderCircuits.FAILURE_THRESHOLD);

        CompletionException refused = assertThrows(CompletionException.class,
                () -> provider.complete(PROMPT, PARAMS).toCompletableFuture().join());
        assertInstanceOf(CircuitOpenException.class, refused.getCause());
    }

    /**
     * Callers already classify failures by unwrapping one CompletionException, so the decorator must
     * not add a layer — an extra wrap would make every LLM failure look unrecognized and fail the
     * review terminally instead of retrying it.
     */
    @Test
    void theCallersViewOfAFailureIsUnchanged() {
        UncheckedIOException original = new UncheckedIOException(new IOException("down"));
        CircuitBreakingLlmProvider provider = guard(failingWith(original));

        CompletionException thrown = assertThrows(CompletionException.class,
                () -> provider.complete(PROMPT, PARAMS).toCompletableFuture().join());

        assertEquals(original, thrown.getCause(), "exactly one wrap, carrying the original cause");
    }

    @Test
    void aSuccessfulCallPassesTheCompletionThrough() {
        Completion completion = new Completion("body", new ModelUsage("test-model", 1, 2, 0));
        CircuitBreakingLlmProvider provider =
                guard(provider(() -> CompletableFuture.completedFuture(completion)));

        assertEquals(completion, provider.complete(PROMPT, PARAMS).toCompletableFuture().join());
        assertEquals("test-provider", provider.id());
    }

    /**
     * A blank base URL means the vendor's own endpoint, so those credentials genuinely share a host
     * and belong on one circuit — but two self-hosted instances must stay independent, or one being
     * down pauses reviews on the other.
     */
    @Test
    void hostKeyingSeparatesInstancesButGroupsTheVendorDefault() {
        assertEquals(CircuitBreakingLlmProvider.hostFor("", "openai"),
                CircuitBreakingLlmProvider.hostFor(null, "openai"));
        assertNotEquals(CircuitBreakingLlmProvider.hostFor(null, "openai"),
                CircuitBreakingLlmProvider.hostFor(null, "anthropic"));

        assertEquals("llm.example.invalid",
                CircuitBreakingLlmProvider.hostFor("https://llm.example.invalid/v1", "openai"));
        assertNotEquals(CircuitBreakingLlmProvider.hostFor("https://llm.example.invalid/v1", "openai"),
                CircuitBreakingLlmProvider.hostFor("https://other.example.invalid/v1", "openai"));
    }

    /** Recovery is the breaker's job too: after the cooldown one probe is admitted. */
    @Test
    void theProviderIsTriedAgainAfterTheCooldown() {
        CircuitBreakingLlmProvider provider = guard(failingWith(new UncheckedIOException(new IOException("down"))));
        failTimes(provider, ProviderCircuits.FAILURE_THRESHOLD);
        int callsWhileOpen = calls.get();

        now.addAndGet(ProviderCircuits.COOLDOWN_MS);

        assertThrows(CompletionException.class,
                () -> provider.complete(PROMPT, PARAMS).toCompletableFuture().join());
        assertTrue(calls.get() > callsWhileOpen, "the cooldown must admit a probe");
    }
}
