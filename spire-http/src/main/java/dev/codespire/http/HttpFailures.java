package dev.codespire.http;

/**
 * Builds the calling adapter's own exception for a non-2xx response or a refused redirect.
 *
 * <p>This exists so the shared client can be strict about transport while each adapter keeps its own
 * exception type: callers catch {@code JiraApiException} or {@code GitHubIssueApiException} narrowly,
 * and each type decides its own policy — notably whether a response-body snippet may appear in the
 * message, which differs because some APIs echo the rejected credential on a 401.
 */
public interface HttpFailures {

    /** @param detail a truncated, secret-free snippet or guard reason; null when there is none. */
    RuntimeException create(int status, String method, String path, String detail);
}
