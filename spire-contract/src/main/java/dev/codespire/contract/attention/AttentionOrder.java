package dev.codespire.contract.attention;

import java.time.Instant;
import java.util.Comparator;

/**
 * The one order the attention bell is shown in, used by every service that sorts its feed; the UI applies the same
 * rule again after it merges the feeds ({@code spire-ui/src/hooks/attentionOrder.ts}).
 *
 * <ol>
 *   <li>{@link AttentionView.Severity#BLOCKING} before {@link AttentionView.Severity#WARNING}: nothing works until a
 *       blocker is fixed.</li>
 *   <li>Rows about a single review ({@link #isReviewRow}) before other warnings: they are the operator's own work
 *       stopping, and a just-refused review used to sit at the bottom of 23 rows because code A–Z put it there.</li>
 *   <li>Newest first by {@link AttentionView#since()}; a row with no known time after every timed one.</li>
 *   <li>Code, then subject: a stable tie-break, so equal rows keep one order across evaluations.</li>
 * </ol>
 */
public final class AttentionOrder {

    /**
     * The prefix every per-review code carries: {@code REVIEW_STUCK}, {@code REVIEW_FAILED}, {@code REVIEW_DEGRADED}
     * and {@code REVIEW_HARNESS_REFUSED}. No other code names a single review: run rows name a factory run, and the
     * cost rows count calls across reviews.
     */
    static final String REVIEW_PREFIX = "REVIEW_";

    private static final int REVIEW_GROUP = 0;
    private static final int OTHER_GROUP = 1;

    public static final Comparator<AttentionView> ORDER = Comparator
            .comparing(AttentionView::severity)
            .thenComparingInt(AttentionOrder::group)
            .thenComparing(AttentionView::since, Comparator.nullsLast(Comparator.<Instant>reverseOrder()))
            .thenComparing(AttentionView::code)
            .thenComparing(AttentionOrder::subjectOrEmpty);

    private AttentionOrder() {
    }

    /** Whether this row is about one review. */
    public static boolean isReviewRow(AttentionView view) {
        return view.code().startsWith(REVIEW_PREFIX);
    }

    private static int group(AttentionView view) {
        return isReviewRow(view) ? REVIEW_GROUP : OTHER_GROUP;
    }

    private static String subjectOrEmpty(AttentionView view) {
        return view.subject() == null ? "" : view.subject();
    }
}
