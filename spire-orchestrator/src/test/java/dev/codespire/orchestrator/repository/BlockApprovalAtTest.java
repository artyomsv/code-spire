package dev.codespire.orchestrator.repository;

import dev.codespire.contract.review.Severity;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** "Block approval at" (V115): which open severities block approval at each level. */
class BlockApprovalAtTest {

    @Test
    void blockerBlocksOnlyABlocker() {
        assertTrue(BlockApprovalAt.BLOCKER.blocks(Severity.BLOCKER));
        assertFalse(BlockApprovalAt.BLOCKER.blocks(Severity.MAJOR), "a MAJOR-only review approves at BLOCKER");
        assertFalse(BlockApprovalAt.BLOCKER.blocks(Severity.MINOR));
    }

    @Test
    void majorBlocksAMajorButNotAMinor() {
        assertTrue(BlockApprovalAt.MAJOR.blocks(Severity.BLOCKER));
        assertTrue(BlockApprovalAt.MAJOR.blocks(Severity.MAJOR));
        assertFalse(BlockApprovalAt.MAJOR.blocks(Severity.MINOR), "a MINOR-only review approves at MAJOR");
    }

    @Test
    void minorBlocksAMinorAndEverythingAbove() {
        assertTrue(BlockApprovalAt.MINOR.blocks(Severity.BLOCKER));
        assertTrue(BlockApprovalAt.MINOR.blocks(Severity.MAJOR));
        assertTrue(BlockApprovalAt.MINOR.blocks(Severity.MINOR));
    }

    @Test
    void infoAndNitNeverBlock() {
        for (BlockApprovalAt level : BlockApprovalAt.values()) {
            assertFalse(level.blocks(Severity.INFO), "INFO at " + level);
            assertFalse(level.blocks(Severity.NIT), "NIT at " + level);
            assertFalse(level.blocks(null), "no severity at " + level);
        }
    }

    @Test
    void parseAcceptsTheThreeLevelsInAnyCaseAndNullIsBlocker() {
        assertEquals(BlockApprovalAt.MAJOR, BlockApprovalAt.parse(" major "));
        assertEquals(BlockApprovalAt.MINOR, BlockApprovalAt.parse("MINOR"));
        assertEquals(BlockApprovalAt.BLOCKER, BlockApprovalAt.parse(null));
    }

    @Test
    void parseRefusesASeverityThatIsNotALevel() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class, () -> BlockApprovalAt.parse("NIT"));
        assertTrue(refused.getMessage().startsWith("Block approval at"), refused.getMessage());
        assertThrows(IllegalArgumentException.class, () -> BlockApprovalAt.parse(""));
    }
}
