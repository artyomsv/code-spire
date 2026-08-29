package dev.codespire.context.code;

import dev.codespire.contract.port.LanguageSupport;

import dev.codespire.contract.port.LanguageSupport.Symbols;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seam between {@code symbolsIn} and {@code callersOf}: what the scanner records has to be what
 * the lookup asks for.
 *
 * <p>Nothing covered this before, and that is exactly where the defect was. Every other rung-2 test
 * either hand-seeds the index or exercises {@code symbolsIn} in isolation, so the suite was green
 * while the production pipeline could not produce a single caller edge on real code.
 *
 * <p>The cause was a filter that excluded imported names from references, reasoned as "an import says
 * what a file COULD use, not what it does". To CALL something from another file you must import it,
 * so the filter removed the name precisely from the files that are callers. `CallSiteContextTest`'s
 * fixture happened to have the caller NOT import the symbol — a shape the pipeline could never
 * produce — so it passed on the one arrangement that hid the bug.
 */
class SymbolIndexSeamTest {

    private final JavaLanguageSupport javaSupport = new JavaLanguageSupport();
    private final TypeScriptLanguageSupport tsSupport = new TypeScriptLanguageSupport();

    /** A real caller: it imports the type, then calls a member on it. */
    private static final String JAVA_CALLER = """
            package dev.example;

            import dev.example.pricing.Pricer;

            public final class Billing {
                public void run() {
                    Pricer.chargeFor(1);
                }
            }
            """;

    @Test
    void aJavaCallerRecordsTheImportedTypeItCalls() {
        Symbols s = javaSupport.symbolsIn(JAVA_CALLER);

        assertTrue(s.references().contains("Pricer"),
                "a file that imports and calls Pricer must be findable as its caller: " + s.references());
        assertTrue(s.references().contains("chargeFor"), s.references().toString());
    }

    /**
     * The same edge for TypeScript, where the defect was total: an ES module imports the callable
     * itself, so excluding imported names left no caller edge of any kind.
     */
    @Test
    void aTypeScriptCallerRecordsTheImportedFunctionItCalls() {
        Symbols s = tsSupport.symbolsIn("""
                import { chargeFor } from '../pricing/pricer';
                import { Button } from './Button';

                export function Billing(rate: number) {
                  return chargeFor(rate);
                }
                """);

        assertTrue(s.references().contains("chargeFor"),
                "an imported callable must remain a reference: " + s.references());
    }

    /** A multi-line braced import still contributes no declaration of its own. */
    @Test
    void aWrappedImportStatementIsSkippedInFull() {
        Symbols s = tsSupport.symbolsIn("""
                import {
                  alpha,
                  beta,
                } from './things';

                export const gamma = 1;
                """);

        assertTrue(s.defines().contains("gamma"), s.defines().toString());
        assertFalse(s.defines().contains("alpha"), "an imported name is not declared here");
        assertFalse(s.defines().contains("beta"), s.defines().toString());
    }

    /** A local is not something another module can call, so it must not consume lookup budget. */
    @Test
    void aTypeScriptFunctionLocalIsNotADeclaration() {
        Symbols s = tsSupport.symbolsIn("""
                export function run() {
                  const total = 1;
                  return total;
                }
                """);

        assertTrue(s.defines().contains("run"), s.defines().toString());
        assertFalse(s.defines().contains("total"), "a function-local const is not a module declaration");
    }

    /**
     * Interface and package-private methods are declarations too. The pattern this replaced required
     * an explicit access modifier, so a change to an interface method would never have triggered a
     * caller lookup for it — and this codebase leans heavily on both shapes.
     */
    @Test
    void javaRecordsInterfaceAndPackagePrivateMethods() {
        Symbols iface = javaSupport.symbolsIn("""
                package dev.example;
                public interface Pricing {
                    long chargeFor(long tokens);
                }
                """);
        assertTrue(iface.defines().contains("chargeFor"), iface.defines().toString());

        Symbols packagePrivate = javaSupport.symbolsIn("""
                package dev.example;
                final class Helper {
                    void sweep() { }
                }
                """);
        assertTrue(packagePrivate.defines().contains("sweep"), packagePrivate.defines().toString());
    }

    /**
     * TypeScript has control words Java does not, and each is one a caller lookup would waste budget
     * on: {@code await x(...)}, {@code typeof f(...)} and {@code for (const v of items(...))} all put
     * a keyword immediately before an argument list, which is exactly the shape
     * {@link SourceText#declaredCallableName} reads as a declaration. The Java test beside this one
     * cannot cover them — they are not Java keywords — so the two scanners need their own cases.
     */
    @Test
    void aTypeScriptControlWordDeclaresNothing() {
        Symbols s = tsSupport.symbolsIn("""
                export async function run(items: string[]) {
                  await load(items);
                  if (typeof check(items) === 'string') {
                    return items;
                  }
                  for (const item of expand(items)) {
                    delete cache(item);
                  }
                }
                """);

        for (String control : List.of("await", "typeof", "of", "delete", "if", "for")) {
            assertFalse(s.defines().contains(control), control + " is not a declaration: " + s.defines());
        }
        assertFalse(s.defines().contains("load"), "a call site does not declare the callee");
        assertTrue(s.defines().contains("run"), s.defines().toString());
    }

    /** A call is not a declaration, however much the line looks like one. */
    @Test
    void aCallOrControlFlowLineDeclaresNothing() {
        Symbols s = javaSupport.symbolsIn("""
                package dev.example;
                class Sample {
                    void go() {
                        if (ready()) {
                            helper(1);
                        }
                        for (int i = 0; i < 3; i++) { }
                    }
                }
                """);

        assertFalse(s.defines().contains("if"), s.defines().toString());
        assertFalse(s.defines().contains("for"), s.defines().toString());
        assertFalse(s.defines().contains("helper"), "a call site does not declare the callee");
        assertTrue(s.defines().contains("go"));
    }

    /**
     * Both scanners must be linear in the input. The patterns these replaced were measured at 28
     * seconds on a 32 KB line and 21 seconds on a 96 KB unterminated comment, on a shared pool whose
     * timeout does not interrupt — a pull request author picks this content.
     */
    @Test
    void scansHostileInputInLinearTime() {
        String longDeclarationLine = "private " + "a".repeat(60_000) + " ";
        String unterminatedComment = "/*x".repeat(30_000);

        for (LanguageSupport support : List.of(javaSupport, tsSupport)) {
            long start = System.nanoTime();
            assertNotNull(support.symbolsIn(longDeclarationLine));
            assertNotNull(support.symbolsIn(unterminatedComment));
            long millis = (System.nanoTime() - start) / 1_000_000;
            assertTrue(millis < 2_000,
                    support.getClass().getSimpleName() + " took " + millis + "ms — quadratic backtracking");
        }
    }

    /** A generated file large enough to be pointless to index is skipped outright. */
    @Test
    void skipsAFileTooLargeToBeWorthScanning() {
        String huge = "class A { }\n".repeat(SourceText.MAX_SCANNED_CHARS / 4);

        assertTrue(javaSupport.symbolsIn(huge).defines().isEmpty(), "an oversized file contributes nothing");
    }
}
