package dev.codespire.scm.github;

import dev.codespire.contract.scm.ScmApiException;

/** Non-2xx response from the GitHub API. 404 on a diff means the commit was force-pushed away. */
public class GitHubApiException extends RuntimeException implements ScmApiException {

    private final int status;
    private final boolean rateLimited;
    private final Integer retryAfterSeconds;

    public GitHubApiException(int status, String method, String path) {
        this(status, method, path, null);
    }

    /** {@code detail} is a truncated, secret-free response-body snippet or guard reason. */
    public GitHubApiException(int status, String method, String path, String detail) {
        this(status, method, path, detail, false, null);
    }

    /** rateLimited marks GitHub's 403-shaped (secondary) and GraphQL rate limits explicitly. */
    public GitHubApiException(int status, String method, String path, String detail,
                              boolean rateLimited, Integer retryAfterSeconds) {
        super(message(status, method, path, detail));
        this.status = status;
        this.rateLimited = rateLimited;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    private static String message(int status, String method, String path, String detail) {
        return "GitHub API " + method + " " + path + " failed with HTTP " + status
                + (detail == null || detail.isBlank() ? "" : ": " + detail);
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public boolean isRateLimited() {
        return status == 429 || rateLimited;
    }

    @Override
    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
