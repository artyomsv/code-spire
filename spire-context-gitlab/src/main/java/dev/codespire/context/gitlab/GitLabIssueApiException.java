package dev.codespire.context.gitlab;

/**
 * Non-2xx response from the GitLab REST API. {@code status} is surfaced so the provider can skip a
 * 404 (a typo'd reference, or an epic on a non-Premium instance) while an auth failure marks the
 * whole contribution ERROR.
 *
 * <p>A 401 or 403 body can echo the token that was rejected, so {@code detail} is dropped for those
 * statuses: the message states the outcome and nothing the upstream said.
 */
public class GitLabIssueApiException extends RuntimeException {

    private final int status;

    /** {@code detail} is a truncated, secret-free snippet — discarded entirely for auth statuses. */
    public GitLabIssueApiException(int status, String method, String path, String detail) {
        super("GitLab API " + method + " " + path + " failed with HTTP " + status
                + (isCredentialOutcome(status) || detail == null || detail.isBlank() ? "" : ": " + detail));
        this.status = status;
    }

    public int status() {
        return status;
    }

    private static boolean isCredentialOutcome(int status) {
        return status == 401 || status == 403;
    }
}
