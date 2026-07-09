package dev.codespire.context.confluence;

/**
 * Non-2xx response from the Confluence REST API. {@code status} is surfaced so the
 * orchestrator's connectivity check can distinguish an auth failure (401/403) from
 * a transient error, and so the context aggregator can mark the contribution ERROR
 * without aborting the whole review. Mirrors {@code JiraApiException}.
 */
public class ConfluenceApiException extends RuntimeException {

    private final int status;

    public ConfluenceApiException(int status, String method, String path) {
        this(status, method, path, null);
    }

    /** {@code detail} is a truncated, secret-free response-body snippet or guard reason. */
    public ConfluenceApiException(int status, String method, String path, String detail) {
        super("Confluence API " + method + " " + path + " failed with HTTP " + status
                + (detail == null || detail.isBlank() ? "" : ": " + detail));
        this.status = status;
    }

    public int status() {
        return status;
    }
}
