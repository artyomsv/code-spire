package dev.codespire.context.github;

/**
 * Non-2xx response from the GitHub REST API. {@code status} is surfaced so the provider can skip a
 * 404 (a typo'd reference) while an auth failure marks the whole contribution ERROR.
 *
 * <p>A 401 or 403 body can echo the token that was rejected, so {@code detail} is dropped for those
 * statuses: the message states the outcome and nothing the upstream said.
 */
public class GitHubIssueApiException extends RuntimeException {

    private final int status;

    public GitHubIssueApiException(int status, String method, String path) {
        this(status, method, path, null);
    }

    /** {@code detail} is a truncated, secret-free snippet — discarded entirely for auth statuses. */
    public GitHubIssueApiException(int status, String method, String path, String detail) {
        super("GitHub API " + method + " " + path + " failed with HTTP " + status
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
