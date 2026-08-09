package dev.codespire.contract.review;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ModelUsage carries a PARTITION of a call's tokens and no money at all. Both properties are load
 * bearing: the partition is what makes summing charge lines correct, and the absence of a cost field
 * is what stops the worker — which holds no price catalog — from reporting one.
 */
class ModelUsageTest {

    @Test
    void tokensOfReturnsTheCountForATypeAndZeroForOneTheVendorDidNotReport() {
        ModelUsage usage = new ModelUsage("TEST-MODEL",
                List.of(new TokenCount(TokenType.INPUT, 120),
                        new TokenCount(TokenType.OUTPUT, 30)),
                150, true);

        assertEquals(120, usage.tokensOf(TokenType.INPUT));
        assertEquals(30, usage.tokensOf(TokenType.OUTPUT));
        assertEquals(0, usage.tokensOf(TokenType.CACHED_INPUT));
    }

    @Test
    void theConvenienceFactoryBuildsATwoTypePartitionThatReconciles() {
        ModelUsage usage = ModelUsage.of("TEST-MODEL", 120, 30);

        assertEquals(150, usage.reportedTotal());
        assertTrue(usage.reconciled());
        assertEquals(2, usage.counts().size());
    }

    /** Defensive copy: a caller mutating its list afterwards must not change a recorded usage. */
    @Test
    void countsAreCopiedNotAliased() {
        List<TokenCount> mutable = new java.util.ArrayList<>();
        mutable.add(new TokenCount(TokenType.INPUT, 5));
        ModelUsage usage = new ModelUsage("TEST-MODEL", mutable, 5, true);

        mutable.clear();

        assertEquals(1, usage.counts().size());
    }

    /** A null counts list is an empty partition, never a NullPointerException downstream. */
    @Test
    void nullCountsBecomeEmpty() {
        assertEquals(0, new ModelUsage("TEST-MODEL", null, 0, true).counts().size());
    }
}
