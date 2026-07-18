package dev.codespire.llm;

import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.Severity;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VerdictsParserTest {

    private final List<PriorFinding> findings = List.of(
            new PriorFinding("src/A.java", 7, Severity.MAJOR, "resource leak", "thread-1"),
            new PriorFinding("src/B.java", 20, Severity.MINOR, "naming", "thread-2"));

    @Test
    void mapsNumberedVerdictsBackToFindings() {
        String output = """
                {"verdicts":[
                  {"id":1,"status":"resolved","note":"try-with-resources added"},
                  {"id":2,"status":"still-open","note":"rename not applied"}]}""";
        List<FindingVerdict> verdicts = VerdictsParser.parse(output, findings);
        assertEquals(2, verdicts.size());
        assertEquals(FindingVerdict.Status.RESOLVED, verdicts.get(0).status());
        assertEquals("thread-1", verdicts.get(0).threadRef());
        assertEquals("src/A.java", verdicts.get(0).path());
        assertEquals(7, verdicts.get(0).line());
        assertEquals(FindingVerdict.Status.STILL_OPEN, verdicts.get(1).status());
    }

    @Test
    void lenientAboutStatusSpellingAndProse() {
        String output = "Sure — here you go:\n{\"verdicts\":[{\"id\":1,\"status\":\"STILL_OPEN\",\"note\":\"x\"},"
                + "{\"id\":2,\"status\":\"Acknowledged\",\"note\":\"y\"}]}";
        List<FindingVerdict> verdicts = VerdictsParser.parse(output, findings);
        assertEquals(FindingVerdict.Status.STILL_OPEN, verdicts.get(0).status());
        assertEquals(FindingVerdict.Status.ACKNOWLEDGED, verdicts.get(1).status());
    }

    @Test
    void unchangedAndNotAddressedMapToUnchanged() {
        String output = "{\"verdicts\":[{\"id\":1,\"status\":\"unchanged\",\"note\":\"x\"},"
                + "{\"id\":2,\"status\":\"NOT_ADDRESSED\",\"note\":\"y\"}]}";
        List<FindingVerdict> verdicts = VerdictsParser.parse(output, findings);
        assertEquals(FindingVerdict.Status.UNCHANGED, verdicts.get(0).status());
        assertEquals(FindingVerdict.Status.UNCHANGED, verdicts.get(1).status());
    }

    @Test
    void unknownIdsAndBadStatusesAreDropped() {
        String output = "{\"verdicts\":[{\"id\":9,\"status\":\"resolved\"},{\"id\":1,\"status\":\"maybe\"}]}";
        assertTrue(VerdictsParser.parse(output, findings).isEmpty());
    }

    @Test
    void garbageOutputYieldsEmptyListNeverThrows() {
        assertTrue(VerdictsParser.parse("not json at all", findings).isEmpty());
        assertTrue(VerdictsParser.parse(null, findings).isEmpty());
    }
}
