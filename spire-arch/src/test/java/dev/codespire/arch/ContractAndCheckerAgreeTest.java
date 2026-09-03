package dev.codespire.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The agent-image contract and its checker must describe the same thing (FR-F13).
 *
 * <p>Both directions, and both matter for different reasons. A clause DOCUMENTED and not checked is
 * a promise nothing keeps — an operator reads it, builds to it, and {@code verify} never says
 * whether they succeeded. A clause CHECKED and not documented is a conformance failure an operator
 * cannot look up: the report prints an id, and the id leads nowhere.
 *
 * <p>The verified and declared sets are checked SEPARATELY rather than merged, because moving a
 * clause between them is exactly the change that must not happen quietly. A clause that becomes
 * verifiable is a real improvement and it changes what the report asserts, so it should require an
 * edit to the document as well as to the code.
 *
 * <p>Read as text, like every other guard in this module, and with the scanned files declared as
 * task inputs — otherwise the check reports a cached pass from the very edit it exists to catch.
 */
class ContractAndCheckerAgreeTest {

    private static final String CONTRACT = "docs/factory/AGENT-IMAGE-CONTRACT.md";

    private static final String CLAUSES =
            "spire-agent-image/src/main/java/dev/codespire/agentimage/Clauses.java";

    /** A clause heading in the contract: {@code ### `id` — title}. */
    private static final Pattern HEADING = Pattern.compile("(?m)^###\\s+`([a-z0-9-]+)`");

    /** A list member in {@code Clauses}: the constant names inside VERIFIED or DECLARED. */
    private static Set<String> declaredList(String source, String listName) throws IOException {
        Matcher list = Pattern
                .compile("List<String>\\s+" + listName + "\\s*=\\s*List\\.of\\(([^;]*)\\);",
                        Pattern.DOTALL)
                .matcher(source);
        assertTrue(list.find(), "no `List<String> " + listName + " = List.of(...)` in " + CLAUSES);

        Set<String> ids = new LinkedHashSet<>();
        Matcher constant = Pattern.compile("([A-Z][A-Z0-9_]*)").matcher(list.group(1));
        while (constant.find()) {
            ids.add(valueOf(source, constant.group(1)));
        }
        return ids;
    }

    /** The string a {@code public static final String NAME = "value";} declaration holds. */
    private static String valueOf(String source, String constant) {
        Matcher value = Pattern
                .compile("String\\s+" + constant + "\\s*=\\s*\"([^\"]+)\"")
                .matcher(source);
        assertTrue(value.find(), "no value for Clauses." + constant);
        return value.group(1);
    }

    /**
     * Clause ids under a given contract section, in document order.
     *
     * <p>Order is part of the comparison: the report renders verified clauses in
     * {@code Clauses.VERIFIED} order, so a document listing them differently is a document an
     * operator reads top-to-bottom against a report that does not match.
     */
    private static List<String> contractClausesUnder(String contract, String heading) {
        int start = contract.indexOf("## " + heading);
        assertTrue(start >= 0, "no `## " + heading + "` section in " + CONTRACT);
        int end = contract.indexOf("\n## ", start + 1);
        String section = end < 0 ? contract.substring(start) : contract.substring(start, end);

        List<String> ids = new ArrayList<>();
        Matcher headings = HEADING.matcher(section);
        while (headings.find()) {
            ids.add(headings.group(1));
        }
        return ids;
    }

    @Test
    void everyVerifiedClauseIsDocumentedAndEveryDocumentedOneIsChecked() throws IOException {
        String contract = RootBuild.read(CONTRACT);
        String clauses = RootBuild.read(CLAUSES);

        List<String> documented = contractClausesUnder(contract, "Verified clauses");
        Set<String> checked = declaredList(clauses, "VERIFIED");

        assertEquals(documented, List.copyOf(checked),
                "the contract's verified section and Clauses.VERIFIED must list the same ids in the "
                        + "same order: a documented-but-unchecked clause is a promise nothing keeps, "
                        + "and a checked-but-undocumented one prints an id that leads nowhere");
    }

    @Test
    void everyDeclaredClauseIsDocumentedAsUnverifiableAndSaysWhy() throws IOException {
        String contract = RootBuild.read(CONTRACT);
        String clauses = RootBuild.read(CLAUSES);

        List<String> documented = contractClausesUnder(contract, "Declared clauses");
        Set<String> declared = declaredList(clauses, "DECLARED");

        assertEquals(documented, List.copyOf(declared),
                "the contract's declared section and Clauses.DECLARED must list the same ids");

        int start = contract.indexOf("## Declared clauses");
        String section = contract.substring(start);
        for (String id : declared) {
            assertTrue(section.contains("`" + id + "`"), id + " is not documented as declared");
        }
        assertTrue(section.contains("cannot be verified"),
                "the declared section must say WHY each clause is unverifiable, or a reader takes "
                        + "the omission for laziness rather than for a limit");
    }

    /**
     * No clause may be in both sets.
     *
     * <p>A clause in both could be reported either way depending on which list a future edit read
     * first, which is the blend the whole two-part report exists to prevent.
     */
    @Test
    void noClauseIsBothVerifiedAndDeclared() throws IOException {
        String clauses = RootBuild.read(CLAUSES);

        Set<String> verified = declaredList(clauses, "VERIFIED");
        Set<String> declared = declaredList(clauses, "DECLARED");

        assertTrue(verified.stream().noneMatch(declared::contains),
                "a clause in both lists could be reported either way");
    }

    /**
     * The contract must document the interface its clauses enforce.
     *
     * <p>Three verified clauses are only passable by an image honouring five environment
     * variables, and an earlier version of the contract named none of them — so an image written
     * from the document alone failed three clauses with nothing to explain why. Ids agreeing is
     * not the same as the document being sufficient, and only this notices the difference.
     */
    @Test
    void theContractDocumentsTheRunTimeInterfaceItsClausesRequire() throws IOException {
        String contract = RootBuild.read(CONTRACT);
        String entrypoint = RootBuild.read("deploy/agent/spire-agent-entrypoint.sh");

        Set<String> required = new LinkedHashSet<>();
        Matcher variables = Pattern.compile("SPIRE_[A-Z_]+").matcher(entrypoint);
        while (variables.find()) {
            required.add(variables.group());
        }
        assertFalse(required.isEmpty(), "the reference entrypoint named no SPIRE_ variable at all");

        for (String variable : required) {
            assertTrue(contract.contains(variable),
                    variable + " is required by the reference entrypoint and documented nowhere in "
                            + CONTRACT + "; an image built from the contract alone would fail a "
                            + "clause with no way to find out why");
        }
    }

    /**
     * The scan must have parsed something.
     *
     * <p>Every assertion above compares two collections, and two empty collections are equal — the
     * vacuity hole {@code ContractSchemaSnapshotTest} shipped with. A renamed document or a
     * restructured constants file must fail here rather than pass by finding nothing.
     */
    @Test
    void theScanFoundBothTheContractAndTheClauses() throws IOException {
        String contract = RootBuild.read(CONTRACT);

        assertFalse(contract.isBlank(), CONTRACT + " read as empty");
        assertTrue(contractClausesUnder(contract, "Verified clauses").size() >= 5,
                "the contract's verified section parsed to almost nothing, so the checks above "
                        + "are comparing empty lists");
        assertFalse(declaredList(RootBuild.read(CLAUSES), "VERIFIED").isEmpty());
        assertFalse(declaredList(RootBuild.read(CLAUSES), "DECLARED").isEmpty());

        assertTrue(HEADING.matcher("### `entrypoint` — the image has an entrypoint").find(),
                "the heading pattern must match the form the contract actually uses");
    }
}
