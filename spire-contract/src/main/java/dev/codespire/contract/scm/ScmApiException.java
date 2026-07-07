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
}
