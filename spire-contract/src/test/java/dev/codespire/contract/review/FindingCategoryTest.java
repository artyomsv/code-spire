package dev.codespire.contract.review;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * The dimension learned memory groups on (P4 / ADR-027).
 *
 * <p>The invariant worth testing is the one that is easy to get backwards: <b>an unrecognised label
 * is null, not {@code OTHER}</b>. {@code OTHER} is an answer the model gave — it looked at the
 * finding and judged that nothing more specific fits. An unparseable label means nobody knows what
 * the model meant. Collapsing those two is the mistake ADR-023 paid for, where four separate places
 * turned <em>unknown</em> into <em>zero</em> and a spend cap built on the result would never have
 * fired.
 */
class FindingCategoryTest {

    @Test
    void parsesTheLabelsTheModelIsAskedFor() {
        assertEquals(FindingCategory.NAMING, FindingCategory.parse("NAMING"));
        assertEquals(FindingCategory.ERROR_HANDLING, FindingCategory.parse("ERROR_HANDLING"));
        assertEquals(FindingCategory.OTHER, FindingCategory.parse("OTHER"));
    }

    /** Models are inconsistent about case and separators; none of that is a different answer. */
    @Test
    void acceptsTheSpellingsAModelActuallyProduces() {
        assertEquals(FindingCategory.TEST_COVERAGE, FindingCategory.parse("test_coverage"));
        assertEquals(FindingCategory.TEST_COVERAGE, FindingCategory.parse("Test-Coverage"));
        assertEquals(FindingCategory.TEST_COVERAGE, FindingCategory.parse("  test coverage  "));
    }

    /**
     * The invariant. An eleventh label will happen eventually, and it must not be silently recorded
     * as the model having chosen {@code OTHER} — a grouping built on that would be counting
     * confusions as a category.
     */
    @Test
    void anUnrecognisedLabelIsUnknownRatherThanOther() {
        assertNull(FindingCategory.parse("ARCHITECTURE"));
        assertNull(FindingCategory.parse("maintainability"));
        assertNull(FindingCategory.parse("¯\\_(ツ)_/¯"));
    }

    /** Absent is the same as unknown, and it is the common case for a customized review prompt. */
    @Test
    void anAbsentOrBlankLabelIsNull() {
        assertNull(FindingCategory.parse(null));
        assertNull(FindingCategory.parse(""));
        assertNull(FindingCategory.parse("   "));
    }

    /**
     * The wither exists because a rebuild site that lists the components compiles fine after a new
     * one is added, and silently drops it — the trap the 2026-08-28 work recorded. So it has to
     * carry everything else through untouched.
     */
    @Test
    void witheringTheCategoryKeepsEveryOtherComponent() {
        Finding original = new Finding("src/A.java", new LineRange(3, 7), Severity.BLOCKER,
                FindingCategory.STYLE, "TEST message", "TEST suggestion");

        Finding withered = original.withCategory(FindingCategory.SECURITY);

        assertEquals(FindingCategory.SECURITY, withered.category());
        assertEquals(original.path(), withered.path());
        assertEquals(original.range(), withered.range());
        assertEquals(original.severity(), withered.severity());
        assertEquals(original.message(), withered.message());
        assertEquals(original.suggestion(), withered.suggestion());
    }

    /** The pre-category constructor still exists for the thirty-odd sites that do not care. */
    @Test
    void theShorterConstructorLeavesTheCategoryUnknown() {
        Finding finding = new Finding("src/A.java", new LineRange(1, 1), Severity.NIT,
                "TEST message", null);

        assertNull(finding.category());
    }

    @Test
    void parseIsIdentityForAValueItAlreadyProduced() {
        for (FindingCategory category : FindingCategory.values()) {
            assertSame(category, FindingCategory.parse(category.name()));
        }
    }
}
