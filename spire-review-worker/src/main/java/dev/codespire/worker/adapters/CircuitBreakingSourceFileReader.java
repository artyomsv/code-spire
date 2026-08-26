package dev.codespire.worker.adapters;

import dev.codespire.context.code.CodeContextApiException;
import dev.codespire.context.code.SourceFileReader;

/**
 * Pauses code-context file reads to a source that is down, the same arrangement
 * {@link CircuitBreakingLlmProvider} uses for the LLM path and {@link RetryingDiffSource} uses for
 * SCM reads. Wrapped here, in the worker, rather than inside {@code spire-context-code}: it needs
 * {@link ProviderCircuits}, which is worker-owned, and ADR-021 forbids that Apache-2.0 module from
 * depending on a service module.
 *
 * <p>Keyed on {@link SourceFileReader#apiHost()} — the same registry the SCM adapters and the LLM
 * path share, so a struggling self-hosted instance pauses only itself, never every instance of the
 * same platform.
 */
public class CircuitBreakingSourceFileReader implements SourceFileReader {

    private final SourceFileReader delegate;
    private final ProviderCircuits circuits;

    public CircuitBreakingSourceFileReader(SourceFileReader delegate) {
        this(delegate, ProviderCircuits.shared());
    }

    CircuitBreakingSourceFileReader(SourceFileReader delegate, ProviderCircuits circuits) {
        this.delegate = delegate;
        this.circuits = circuits;
    }

    @Override
    public String read(String repo, String path, String commit) {
        return circuits.guard(delegate.apiHost(), () -> delegate.read(repo, path, commit),
                CircuitBreakingSourceFileReader::isUnhealthy);
    }

    @Override
    public String apiHost() {
        return delegate.apiHost(); // local, never fails — and the key this class's breaker uses
    }

    /**
     * The wrapped reader. Every {@code code} provider is built around one of these, so a test asking
     * which platform's reader a credential resolved to has to be able to see past the wrapper —
     * see {@code CodeContextProvider.reader()}.
     */
    public SourceFileReader delegate() {
        return delegate;
    }

    /**
     * Only a real server-side failure counts against the shared circuit. A 404 — an absent or moved
     * file — is the normal case per {@link SourceFileReader#read}'s own contract, not illness; a
     * 401/403 means this credential or this repository, not the host, is the problem. Counting either
     * would let one repository with reorganized paths, or one bad token, pause reviewing for every
     * other repository sharing this host's circuit with the SCM adapters.
     */
    private static boolean isUnhealthy(RuntimeException e) {
        return e instanceof CodeContextApiException api && api.status() >= 500;
    }
}
