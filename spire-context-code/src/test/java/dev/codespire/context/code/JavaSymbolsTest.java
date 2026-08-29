package dev.codespire.context.code;

import dev.codespire.contract.port.LanguageSupport;
import dev.codespire.contract.port.LanguageSupport.Symbols;
import dev.codespire.contract.scm.FilePatch;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * What a Java file declares, and what it merely mentions — the input to rung 2's index (ADR-026 §7).
 *
 * <p>Regex rather than a parser, on purpose: the index produces CANDIDATES that the caller re-fetches
 * and confirms before citing, so imprecision costs a wasted fetch rather than a wrong finding. These
 * tests pin the shape rather than demand parser-grade accuracy.
 */
class JavaSymbolsTest {

    private final JavaLanguageSupport support = new JavaLanguageSupport();

    private static final String SOURCE = """
            package com.billing;

            import com.pricing.Pricer;
            import java.util.List;

            /** Charges a call. Mentions Ghost in a comment only. */
            public final class Billing {

                private static final String LABEL = "Pricer in a string literal";

                public long chargeFor(long tokens) {
                    return Pricer.chargeFor(tokens, RATE);
                }

                private void helper() {
                    Collector.collect();
                }
            }
            """;

    @Test
    void declaresItsTypeAndItsMethods() {
        Symbols s = support.symbolsIn(SOURCE);

        assertTrue(s.defines().contains("Billing"), s.defines().toString());
        assertTrue(s.defines().contains("chargeFor"), s.defines().toString());
        assertTrue(s.defines().contains("helper"), s.defines().toString());
    }

    @Test
    void mentionsATypeItCallsButDoesNotDeclare() {
        assertTrue(support.symbolsIn(SOURCE).references().contains("Collector"));
    }

    /**
     * An imported name that the body USES is a reference, and this is the single most important thing
     * this scanner gets right.
     *
     * <p>This test previously asserted the opposite, on the reasoning that "an import says what a file
     * COULD use, not what it does". That reasoning is sound and the conclusion was wrong: to CALL
     * something from another file you must import it, so excluding imported names removed the name
     * precisely from the files that are callers, and `callersOf("Pricer")` returned nothing. The guard
     * actually wanted is skipping the import STATEMENTS, which happens separately.
     *
     * <p>An import the body never uses still contributes nothing, because only the statement lines
     * mention it — which is the property the old filter was there to provide, obtained for free.
     */
    @Test
    void countsAnImportedNameTheBodyActuallyUses() {
        Symbols s = support.symbolsIn(SOURCE);

        assertTrue(s.references().contains("Pricer"),
                "a file that imports and calls Pricer must be findable as its caller: " + s.references());
        assertFalse(s.references().contains("List"),
                "List is imported but never used in the body, so only its import line mentions it");
    }

    @Test
    void ignoresCommentsAndStringLiterals() {
        Symbols s = support.symbolsIn(SOURCE);
        assertFalse(s.references().contains("Ghost"), "a name in a comment is not a reference");
        assertFalse(s.defines().contains("Ghost"));
    }

    @Test
    void neverReportsAKeywordAsASymbol() {
        Symbols s = support.symbolsIn(SOURCE);
        for (String keyword : new String[] {"public", "final", "class", "return", "private", "static"}) {
            assertFalse(s.references().contains(keyword), keyword + " is a keyword, not a symbol");
        }
    }

    /** A name cannot be both, or a file would appear to call what it declares. */
    @Test
    void aDeclaredNameIsNotAlsoAReference() {
        Symbols s = support.symbolsIn(SOURCE);
        for (String declared : s.defines()) {
            assertFalse(s.references().contains(declared), declared + " is declared here, not referenced");
        }
    }

    @Test
    void contributesNothingForEmptyInput() {
        assertSame(Symbols.NONE, support.symbolsIn(null));
        assertSame(Symbols.NONE, support.symbolsIn("   "));
    }

    /**
     * A language with no implementation contributes nothing and its reviews proceed exactly as
     * before — the promise {@code LanguageSupport} makes about adding a language being a new bean.
     */
    @Test
    void aLanguageThatHasNotImplementedItContributesNothing() {
        assertSame(Symbols.NONE, new UnimplementedLanguage().symbolsIn(SOURCE));
    }

    /** Overrides only what the interface requires, leaving {@code symbolsIn} at its default. */
    private static final class UnimplementedLanguage implements LanguageSupport {
        @Override
        public Set<String> languages() {
            return Set.of("nothing");
        }

        @Override
        public Set<String> identifiersIn(FilePatch patch) {
            return Set.of();
        }

        @Override
        public List<ImportRef> importsIn(String fileContent) {
            return List.of();
        }

        @Override
        public List<String> candidatePaths(ImportRef ref, String importingPath) {
            return List.of();
        }
    }
}
