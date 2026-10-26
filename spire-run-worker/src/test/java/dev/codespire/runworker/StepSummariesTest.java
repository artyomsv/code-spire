package dev.codespire.runworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codespire.contract.work.StepSummary;
import dev.codespire.secrets.SecretScrub;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reading the step summary a build handed over (M4 slice 8, §6.8, A9, A13): capped to 20 items of 500 characters,
 * scrubbed of the run's credentials, stripped of control characters, and refused whole only when it is not an object.
 */
class StepSummariesTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    private static final String KEY = "TEST-key-abcdefgh";

    private static final SecretScrub SCRUB = SecretScrub.of(List.of(new SecretScrub.Credential(null, KEY)));

    private static StepSummary parsed(String json) {
        return StepSummaries.parse(json, SCRUB).orElseThrow();
    }

    @Test
    void aSummaryIsCappedAndScrubbed() {
        ObjectNode root = JSON.createObjectNode();
        ArrayNode decisions = root.putArray("decisions");
        decisions.add("TEST-decision quoting " + KEY);
        decisions.add("TEST-" + "x".repeat(2_000));
        for (int i = 2; i < 10_000; i++) decisions.add("TEST-decision-" + i);

        StepSummary summary = parsed(root.toString());

        assertEquals(StepSummary.MAX_ITEMS, summary.decisions().size());
        assertTrue(summary.decisions().getFirst().startsWith("TEST-decision quoting"), summary.decisions().getFirst());
        assertFalse(summary.decisions().getFirst().contains(KEY), "the run's credential reached the summary");
        String cut = summary.decisions().get(1);
        assertEquals(StepSummary.MAX_ITEM_CHARS, cut.codePointCount(0, cut.length()));
        assertTrue(cut.endsWith("…"), "a cut item says it was cut");
        assertEquals(List.of(), summary.alternativesRejected());
        assertEquals(List.of(), summary.deviations());
    }

    @Test
    void anItemIsCutOnACharacterBoundary() {
        // 600 emoji: a cut by UTF-16 unit would split a surrogate pair at the boundary.
        String emoji = "😀".repeat(600);
        ObjectNode root = JSON.createObjectNode();
        root.putArray("deviations").add(emoji);

        String cut = parsed(root.toString()).deviations().getFirst();

        assertEquals(StepSummary.MAX_ITEM_CHARS, cut.codePointCount(0, cut.length()));
        assertEquals("😀".repeat(StepSummary.MAX_ITEM_CHARS - 1) + "…", cut);
    }

    @Test
    void aNonObjectIsMissing() {
        assertEquals(Optional.empty(), StepSummaries.parse("[1]", SCRUB));
        assertEquals(Optional.empty(), StepSummaries.parse("\"TEST-text\"", SCRUB));
        assertEquals(Optional.empty(), StepSummaries.parse("{TEST-not json", SCRUB));
    }

    @Test
    void unknownFieldsAreIgnored() {
        StepSummary summary = parsed("{\"decisions\":[\"TEST-decision\"],\"TEST-extra\":{\"a\":1},"
                + "\"alternativesRejected\":[\"TEST-alternative\", 7]}");

        assertEquals(List.of("TEST-decision"), summary.decisions());
        assertEquals(List.of("TEST-alternative"), summary.alternativesRejected(), "a non-text item is skipped");
        assertEquals(List.of(), summary.deviations());
    }

    @Test
    void controlCharactersAreStripped() {
        ObjectNode root = JSON.createObjectNode();
        root.putArray("decisions").add("\u001B[31mTEST-red\u0007 line\nnext");

        assertEquals(List.of("TEST-red line\nnext"), parsed(root.toString()).decisions());
    }

    @Test
    void aBacktickRunIsKeptVerbatim() {
        ObjectNode root = JSON.createObjectNode();
        root.putArray("decisions").add("```TEST-text```");

        assertEquals(List.of("```TEST-text```"), parsed(root.toString()).decisions());
    }
}
