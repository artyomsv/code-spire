package dev.codespire.worker.pipeline;

import dev.codespire.scm.github.GitHubApiException;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The words a label failure is logged, stored and published with: kind and HTTP status, never the message, which for a
 * forge failure holds a snippet of the response body (review of PR #458, security/M-1).
 */
class LabelFailureReasonTest {

    private static final String CANARY = "TEST-SECRET-token-echo";

    @Test
    void aForgeFailureIsItsKindAndStatusWithoutTheBody() {
        GitHubApiException forge = new GitHubApiException(403, "POST", "/TEST/labels", CANARY);
        assertEquals("GitHubApiException: the forge answered HTTP 403", LabelFailureReason.of(forge));
    }

    @Test
    void aWrappedForgeFailureIsFoundInTheCauseChain() {
        GitHubApiException forge = new GitHubApiException(500, "GET", "/TEST/pulls/9", CANARY);
        RuntimeException wrapped = new IllegalStateException("TEST wrapper " + CANARY, forge);
        assertEquals("GitHubApiException: the forge answered HTTP 500",
                LabelFailureReason.of(new CompletionException(wrapped)));
    }

    @Test
    void anyOtherFailureIsOnlyItsKind() {
        assertEquals("IllegalStateException", LabelFailureReason.of(new IllegalStateException(CANARY)));
    }
}
