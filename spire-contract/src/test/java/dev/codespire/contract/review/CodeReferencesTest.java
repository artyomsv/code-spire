package dev.codespire.contract.review;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
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
}
