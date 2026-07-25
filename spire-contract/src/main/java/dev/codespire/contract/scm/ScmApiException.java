package dev.codespire.contract.scm;

/**
 * Provider-neutral shape of a failed SCM API call, implemented by every
 * adapter's exception type. Workers classify failures against this interface
 * instead of a concrete provider: 404 on a diff means the commit was
 * force-pushed away (abandon quietly), 429/5xx are transient (retryable).
 */
public interface ScmApiException {

    /** The HTTP status of the failed call. */
    int status();

    default boolean isNotFound() {
        return status() == 404;
    }

    default boolean isRateLimited() {
        return status() == 429;
    }

    /**
     * The provider refused to produce the diff because it is too large — terminal, since
     * retrying cannot shrink the PR.
     *
     * <p>Which response means this is the adapter's business: one API answers a status code,
     * another reports oversize as data on the diff itself (and so never reports it here).
     * Callers ask this instead of matching a status they would have to know a provider to
     * interpret.
     */
    default boolean isDiffTooLarge() {
        return false;
    }

    /** Seconds the provider asked us to wait (Retry-After); null when unknown. */
    default Integer retryAfterSeconds() {
        return null;
    }
}
