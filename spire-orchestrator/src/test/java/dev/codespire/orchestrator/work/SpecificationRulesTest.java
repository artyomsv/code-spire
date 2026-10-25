package dev.codespire.orchestrator.work;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/** What a written specification must hold before anyone is asked to approve it (M4 slice 3, §6.3, A8). */
class SpecificationRulesTest {
    static final String VALID = """
            ## Outcome
            TEST-outcome

            ## Context
            TEST-context

            ## Acceptance criteria
            - TEST-criterion

            ## Out of scope
            TEST-nothing

            ## Open questions
            None
            """;

    static String without(String heading) {
        return VALID.replace("## " + heading + "\n", "");
    }

    @Test
    void allFiveHeadingsAndOneCriterionPass() {
        assertEquals(Optional.empty(), SpecificationRules.check(VALID));
    }

    @ParameterizedTest
    @ValueSource(strings = {"Outcome", "Context", "Acceptance criteria", "Out of scope", "Open questions"})
    void aMissingHeadingFailsNamingIt(String heading) {
        assertEquals(Optional.of("missing heading: " + heading), SpecificationRules.check(without(heading)));
    }

    @Test
    void headingsOutOfOrderFail() {
        String swapped = VALID.replace("## Outcome\nTEST-outcome", "## TEST-swap").replace("## Context\nTEST-context", "## Outcome\nTEST-outcome")
                .replace("## TEST-swap", "## Context\nTEST-context");

        assertTrue(SpecificationRules.check(swapped).orElseThrow().contains("order"));
    }

    @Test
    void anEmptyCriteriaListFails() {
        assertEquals(Optional.of("acceptance criteria need at least one list item"),
                SpecificationRules.check(VALID.replace("- TEST-criterion", "TEST-prose only")));
    }

    @Test
    void aListItemUnderAnotherHeadingIsNotACriterion() {
        String moved = VALID.replace("- TEST-criterion", "TEST-prose").replace("TEST-nothing", "- TEST-not-a-criterion");

        assertEquals(Optional.of("acceptance criteria need at least one list item"), SpecificationRules.check(moved));
    }

    @Test
    void aNumberedCriterionCounts() {
        assertEquals(Optional.empty(), SpecificationRules.check(VALID.replace("- TEST-criterion", "1. TEST-criterion")));
    }

    @Test
    void openQuestionsMaySayNone() {
        assertTrue(VALID.endsWith("## Open questions\nNone\n"));
        assertEquals(Optional.empty(), SpecificationRules.check(VALID));
    }

    @Test
    void aSpecificationOver16KiBFails() {
        String over = VALID + "x".repeat(16 * 1024 + 1 - VALID.getBytes(StandardCharsets.UTF_8).length);

        assertTrue(SpecificationRules.check(over).orElseThrow().contains("16 KiB"));
    }

    @Test
    void aSpecificationOfExactly16KiBPasses() {
        String exact = VALID + "x".repeat(16 * 1024 - VALID.getBytes(StandardCharsets.UTF_8).length);

        assertEquals(Optional.empty(), SpecificationRules.check(exact));
    }

    @Test
    void controlCharactersDoNotCountTowardTheCap() {
        String exact = VALID + "x".repeat(16 * 1024 - VALID.getBytes(StandardCharsets.UTF_8).length) + "\u0007";

        assertEquals(Optional.empty(), SpecificationRules.check(exact));
    }
}
