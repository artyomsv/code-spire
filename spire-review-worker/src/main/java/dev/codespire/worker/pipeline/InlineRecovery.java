package dev.codespire.worker.pipeline;

import dev.codespire.contract.event.IntegrationEvent.CommentsPosted;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Recovers inline comment ids from a previous partially-completed PostComments attempt. Package-private; moved out of
 * {@link ReviewWorker}, which already held more than a reader can.
 */
final class InlineRecovery {

    /** Claim slots that are not posted inline comments, by exact key. */
    private static final Set<String> NON_INLINE_KEYS = Set.of(ReviewWorker.SUMMARY_KEY, NativeReviewPost.REVIEW_KEY,
            ReviewWorker.LLM_KEY, ReconcileCall.RECONCILE_KEY, VerdictLabels.CLAIM_KEY);
    /** Claim slots that are not posted inline comments, by prefix. */
    private static final List<String> NON_INLINE_PREFIXES = List.of("reply:", "resolve:", HarnessReviewWorker.CLAIM_PREFIX);

    private InlineRecovery() {
    }

    /**
     * Adds every claimed inline comment {@code posted} lacks. Skips the non-inline claims (SUMMARY, REVIEW, LLM[:reconcile],
     * LABEL, reply:, resolve:, harness:) — none of those are posted inline comments, so they must never leak into the
     * inline list.
     */
    static void recover(Map<String, String> previouslyPosted, List<CommentsPosted.PostedInline> posted) {
        previouslyPosted.forEach((key, ref) -> {
            if (isInline(key) && posted.stream().noneMatch(p -> p.threadRef().equals(ref))) {
                posted.add(new CommentsPosted.PostedInline(ref, key, 0));
            }
        });
    }

    static boolean isInline(String key) {
        return !NON_INLINE_KEYS.contains(key) && NON_INLINE_PREFIXES.stream().noneMatch(key::startsWith);
    }
}
