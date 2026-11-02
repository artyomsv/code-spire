package dev.codespire.llm;

import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.HarnessReviewInputs;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.ThreadTranscript;

import java.util.List;
import java.util.Map;

/**
 * The files a harness review's agent reads at {@code /inputs} (harness reviews, step 6): {@code context.md}, and on a
 * re-review {@code prior-findings.md}. Pure: every value is fenced as untrusted data, and each file is cut at its cap
 * with a visible marker.
 */
public final class HarnessReviewInputFiles {

    static final int CONTEXT_MAX_BYTES = 128 * 1024;

    static final int PRIOR_FINDINGS_MAX_BYTES = 96 * 1024;

    private HarnessReviewInputFiles() {
    }

    /**
     * The earlier review a re-review judges: its head, its open findings numbered from 1, their threads, and whether
     * the agent can diff from that head ({@code incremental}) or must judge against the current code.
     */
    public record Prior(String priorHead, List<PriorFinding> numbered, Map<String, ThreadTranscript> transcripts,
                        boolean incremental) {
    }

    public static HarnessReviewInputs firstReview(PullRequest pr, List<ContextItem> context) {
        throw new UnsupportedOperationException("not built yet");
    }

    public static HarnessReviewInputs reReview(PullRequest pr, List<ContextItem> context, Prior prior) {
        throw new UnsupportedOperationException("not built yet");
    }
}
