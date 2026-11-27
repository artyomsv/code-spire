package dev.codespire.worker.pipeline;

import dev.codespire.contract.scm.ScmApiException;

/**
 * A label failure in words safe to log, store, publish and show: the failure's kind and, from a forge, its HTTP status.
 * Never the exception's message, which for a forge failure carries a snippet of the response body, and a forge or a
 * proxy may echo a credential there (review of PR #458, security/M-1). Shared by the verdict label and the ci-failed
 * label, for both their log lines and their reported reasons.
 */
final class LabelFailureReason {

    private LabelFailureReason() {
    }

    static String of(RuntimeException e) {
        Throwable cause = WorkerFailures.unwrap(e);
        for (Throwable t = cause; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ScmApiException api) {
                return t.getClass().getSimpleName() + ": the forge answered HTTP " + api.status();
            }
        }
        return cause.getClass().getSimpleName();
    }
}
