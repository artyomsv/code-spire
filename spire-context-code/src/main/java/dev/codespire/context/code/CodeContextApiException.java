package dev.codespire.context.code;

/**
 * Non-2xx response from a {@link SourceFileReader}'s platform API. Mirrors the shape
 * {@code ScmApiException} already uses in {@code spire-contract} so a later task can key a per-host
 * circuit breaker off the same distinctions: a 404 (an absent or renamed file) must not count toward
 * the breaker, while a 5xx must.
 *
 * <p>A 401 or 403 body can echo the token that was rejected, so {@code detail} is dropped for those
 * statuses: the message states the outcome and nothing the upstream said.
 */
public class CodeContextApiException extends RuntimeException {

    private final int status;

    /** {@code detail} is a truncated, secret-free snippet — discarded entirely for auth statuses. */
    public CodeContextApiException(int status, String method, String path, String detail) {
        super("Code context API " + method + " " + path + " failed with HTTP " + status
                + (isCredentialOutcome(status) || detail == null || detail.isBlank() ? "" : ": " + detail));
        this.status = status;
    }

    public int status() {
        return status;
    }

    public boolean isNotFound() {
        return status == 404;
    }

    /**
     * Deliberately 401-only, matching {@code ScmApiException.isUnauthorized()}: at least one of these
     * platforms overloads 403 for rate limiting as well as permission denial, so treating 403 as a dead
     * credential would misreport a throttled repository as a broken token.
     */
    public boolean isUnauthorized() {
        return status == 401;
    }

    /**
     * Seconds the provider asked us to wait; always {@code null} here. {@link
     * dev.codespire.http.PinnedJsonClient}'s failure callback does not surface response headers to the
     * exception it builds, so there is nothing to parse a {@code Retry-After} value from yet.
     */
    public Integer retryAfterSeconds() {
        return null;
    }

    private static boolean isCredentialOutcome(int status) {
        return status == 401 || status == 403;
    }
}
