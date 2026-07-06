package dev.codespire.scm.github;

/** Non-2xx response from the GitHub API. 404 on a diff means the commit was force-pushed away. */
public class GitHubApiException extends RuntimeException {

    private final int status;

    public GitHubApiException(int status, String method, String path) {
        super("GitHub API " + method + " " + path + " failed with HTTP " + status);
        this.status = status;
    }

    public int status() {
        return status;
    }

    public boolean isNotFound() {
        return status == 404;
    }
}
