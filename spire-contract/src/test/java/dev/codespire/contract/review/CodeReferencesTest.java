package dev.codespire.contract.review;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CodeReferencesTest {

    @Test
    void emptyIsTheAbsentCase() {
        assertTrue(CodeReferences.empty().isEmpty());
        assertEquals(Set.of(), CodeReferences.empty().changedPaths());
        assertEquals(Set.of(), CodeReferences.empty().identifiers());
    }

    @Test
    void bothSetsAreDefensivelyCopied() {
        Set<String> paths = new HashSet<>(Set.of("src/Alpha.java"));
        CodeReferences refs = new CodeReferences(paths, Set.of("betaSymbol"));
        paths.add("src/Gamma.java");
        assertEquals(Set.of("src/Alpha.java"), refs.changedPaths());
        assertThrows(UnsupportedOperationException.class, () -> refs.identifiers().add("x"));
    }

    @Test
    void nullSetsBecomeEmptyRatherThanNull() {
        CodeReferences refs = new CodeReferences(null, null);
        assertEquals(Set.of(), refs.changedPaths());
        assertEquals(Set.of(), refs.identifiers());
        assertTrue(refs.isEmpty());
    }

    /**
     * The size of this value is decided by the pull request, and it rides the Kafka wire. Past the
     * broker's max.message.bytes the produce fails AFTER the diff was fetched, so the review
     * dead-letters with nothing posted — at any author's option. Truncating loses a little resolution
     * recall on a pathological diff instead (PR 63 review).
     */
    @Test
    void bothSetsAreTruncatedSoTheValueCannotOutgrowTheWire() {
        Set<String> paths = new LinkedHashSet<>();
        for (int i = 0; i < CodeReferences.MAX_CHANGED_PATHS + 50; i++) {
            paths.add("src/main/java/dev/example/Alpha" + i + ".java");
        }
        Set<String> identifiers = new LinkedHashSet<>();
        for (int i = 0; i < CodeReferences.MAX_IDENTIFIERS + 50; i++) {
            identifiers.add("symbol" + i);
        }

        CodeReferences refs = new CodeReferences(paths, identifiers);

        assertEquals(CodeReferences.MAX_CHANGED_PATHS, refs.changedPaths().size());
        assertEquals(CodeReferences.MAX_IDENTIFIERS, refs.identifiers().size());
        // Insertion order decides what survives, so a redelivery of the same diff truncates the same way.
        assertTrue(refs.identifiers().contains("symbol0"));
        assertTrue(refs.changedPaths().contains("src/main/java/dev/example/Alpha0.java"));
    }

    /** A normal review is nowhere near the caps, and must come through untouched. */
    @Test
    void aSetUnderTheCapIsKeptWhole() {
        Set<String> identifiers = new LinkedHashSet<>();
        for (int i = 0; i < 200; i++) {
            identifiers.add("symbol" + i);
        }

        CodeReferences refs = new CodeReferences(Set.of("src/Alpha.java"), identifiers);

        assertEquals(200, refs.identifiers().size());
    }
}
