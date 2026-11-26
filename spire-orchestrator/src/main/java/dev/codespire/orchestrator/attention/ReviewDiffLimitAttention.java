package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionView;
import dev.codespire.contract.attention.AttentionView.Severity;
import dev.codespire.orchestrator.settings.ReviewDiffLimit;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.List;

/**
 * {@code DIFF_LIMIT_INVALID}: the stored Review diff limit is not a whole number in range, so every API review is
 * packed with the lowest limit, today's fixed budgets, until it is saved again (PR 456, code-quality/M-5). Not a
 * {@code REVIEW_} code: it names a setting, not one review. Derived on each read, so it clears when the row is fixed.
 */
@ApplicationScoped
public class ReviewDiffLimitAttention {

    static final String CODE = "DIFF_LIMIT_INVALID";

    @Inject
    ReviewDiffLimit diffLimit;

    void collect(List<AttentionView> rows) {
        if (!diffLimit.shown().invalidStoredValue()) {
            return;
        }
        rows.add(new AttentionView(CODE, Severity.WARNING, ReviewDiffLimit.KEY,
                "The stored Review diff limit is not a whole number from 24,000 to 2,000,000, so every API review's "
                        + "diff is capped at 24,000 tokens. Save the limit again in Settings → General, or save it "
                        + "blank to clear it.", "/settings/general"));
    }
}
