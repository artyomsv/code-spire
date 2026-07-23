package dev.codespire.scm.gitlab;

import dev.codespire.contract.scm.ScmApiException;

/** Non-2xx response from the GitLab API. 404 on a diff means the commit was force-pushed away. */
public class GitLabApiException extends RuntimeException implements ScmApiException {

    private final int status;
    private final Integer retryAfterSeconds;

    public GitLabApiException(int status, String method, String path) {
        this(status, method, path, null, null);
    }

    /** {@code detail} is a truncated, secret-free response-body snippet or guard reason. */
    public GitLabApiException(int status, String method, String path, String detail) {
        this(status, method, path, detail, null);
    }

    public GitLabApiException(int status, String method, String path, String detail, Integer retryAfterSeconds) {
        super("GitLab API " + method + " " + path + " failed with HTTP " + status
                + (detail == null || detail.isBlank() ? "" : ": " + detail));
        this.status = status;
        this.retryAfterSeconds = retryAfterSeconds;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public Integer retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
