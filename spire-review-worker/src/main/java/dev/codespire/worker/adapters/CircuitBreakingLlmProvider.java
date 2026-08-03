package dev.codespire.worker.adapters;

import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.llm.ModelParams;
import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.port.LlmProvider;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * Pauses calls to an LLM provider that is down, the way {@link RetryingDiffSource} does for SCM
 * reads — but this is the path with money attached. A degraded provider is billed for on every
 * attempt, and ADR-016's budget re-drives the whole pipeline, so an outage costs a full ladder of
 * paid calls per review until the attempt budget is spent.
 *
 * <p><b>The failure has to be joined to be seen.</b> {@code LangChain4jLlmProvider.complete} returns
 * a <i>failed future</i> rather than throwing — it catches its own RuntimeException to implement the
 * retry-without-temperature fallback. A breaker wrapped naively around the call would therefore
 * record every outage as a success and never open, while looking perfectly installed. So the stage
 * is joined inside the guard.
 *
 * <p>Joining does not change what callers see. {@code join()} reports an already-{@code
 * CompletionException} failure as-is rather than wrapping it again, so re-failing the future with
 * whatever the guard threw still surfaces as one {@code CompletionException} carrying the original
 * cause — which is what both workers unwrap to classify a failure. (An explicit unwrap here was
 * tried first; mutation testing showed it changed nothing observable, so it is documented rather
 * than kept.)
 */
public class CircuitBreakingLlmProvider implements LlmProvider {

    private final LlmProvider delegate;
    private final String host;
    private final ProviderCircuits circuits;

    public CircuitBreakingLlmProvider(LlmProvider delegate, String host, ProviderCircuits circuits) {
        this.delegate = delegate;
        this.host = host;
        this.circuits = circuits;
    }

    /**
     * The host a credential's calls land on, which is what the circuit is keyed by — one struggling
     * self-hosted endpoint must not pause reviews on every other instance of the same platform.
     *
     * <p>A blank base URL is not an unknown host: it means the client falls back to the vendor's own
     * endpoint, so every credential of that type genuinely shares one host and belongs on one
     * circuit. Keying those by type is therefore accurate rather than a collapse — unlike keying
     * <i>all</i> credentials by type, which is the mistake {@code DiffSource.apiHost()} exists to
     * avoid.
     */
    public static String hostFor(String baseUrl, String type) {
        if (baseUrl == null || baseUrl.isBlank()) {
            return "default-endpoint:" + type;
        }
        try {
            String resolved = URI.create(baseUrl.trim()).getHost();
            return resolved != null ? resolved : baseUrl.trim();
        } catch (IllegalArgumentException e) {
            // Unparseable base URLs are rejected when the provider is saved; if one reaches here it
            // still keys consistently, which is all the breaker needs.
            return baseUrl.trim();
        }
    }

    @Override
    public String id() {
        return delegate.id();
    }

    @Override
    public CompletionStage<Completion> complete(Prompt prompt, ModelParams params) {
        try {
            return CompletableFuture.completedFuture(
                    circuits.guard(host, () -> await(prompt, params), LlmFailures::isProviderUnwell));
        } catch (RuntimeException e) {
            return CompletableFuture.failedFuture(e);
        }
    }

    private Completion await(Prompt prompt, ModelParams params) {
        return delegate.complete(prompt, params).toCompletableFuture().join();
    }
}
