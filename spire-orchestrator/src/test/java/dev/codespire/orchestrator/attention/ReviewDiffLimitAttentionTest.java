package dev.codespire.orchestrator.attention;

import dev.codespire.contract.attention.AttentionOrder;
import dev.codespire.contract.attention.AttentionView;
import dev.codespire.orchestrator.settings.ReviewDiffLimit;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** PR 456, code-quality/M-5: an invalid stored Review diff limit raises one row naming the setting, and only then. */
class ReviewDiffLimitAttentionTest {

    @Test
    void anInvalidStoredLimitRaisesARowThatPointsAtGeneralSettings() {
        List<AttentionView> rows = collectWith(new ReviewDiffLimit.Shown(null, true));

        assertEquals(1, rows.size());
        AttentionView row = rows.getFirst();
        assertEquals(ReviewDiffLimitAttention.CODE, row.code());
        assertEquals(ReviewDiffLimit.KEY, row.subject());
        assertEquals("/settings/general", row.action());
        assertNull(row.dismiss(), "it clears when the row is saved again, like every derived row");
        assertFalse(AttentionOrder.isReviewRow(row), "it names a setting, not one review");
    }

    @Test
    void aValidOrAbsentLimitRaisesNothing() {
        assertTrue(collectWith(new ReviewDiffLimit.Shown(60_000, false)).isEmpty());
        assertTrue(collectWith(new ReviewDiffLimit.Shown(null, false)).isEmpty());
    }

    private static List<AttentionView> collectWith(ReviewDiffLimit.Shown shown) {
        ReviewDiffLimitAttention attention = new ReviewDiffLimitAttention();
        attention.diffLimit = new ReviewDiffLimit() {
            @Override
            public Shown shown() {
                return shown;
            }
        };
        List<AttentionView> rows = new ArrayList<>();
        attention.collect(rows);
        return rows;
    }
}
