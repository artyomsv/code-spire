package dev.codespire.runworker;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An artifact larger than one outcome line travels as ordered parts (harness reviews, step 4): the publisher's line is
 * capped at 64 KiB and the runtime clips a longer one, so a 256 KiB review answer cannot travel whole. The parts are
 * reassembled here against the unit's cap, and a gap, a duplicate or a total over the cap never reads as an answer.
 */
class PublisherOutcomePartsTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    /** The plan's bound on one part: 32 KiB of raw text, so every part line stays under the 64 KiB line cap. */
    private static final int PART_CHARS = 32 * 1024;

    private static final int REVIEW_CAP = 256 * 1024;

    private static final int DRAFT_CAP = 32 * 1024;

    private static String part(int index, int count, String text) {
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("event", "artifact_part");
        line.put("index", index);
        line.put("count", count);
        line.put("text", text);
        try {
            return JSON.writeValueAsString(line);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("a test line could not be written", e);
        }
    }

    /** The text cut into parts of at most {@link #PART_CHARS} ASCII characters, as the publisher sends them. */
    private static List<String> parts(String text) {
        List<String> lines = new ArrayList<>();
        int count = (text.length() + PART_CHARS - 1) / PART_CHARS;
        for (int index = 0; index < count; index++) {
            int end = Math.min(text.length(), (index + 1) * PART_CHARS);
            lines.add(part(index, count, text.substring(index * PART_CHARS, end)));
        }
        return lines;
    }

    private static PublisherOutcome read(int cap, List<String> lines) {
        PublisherOutcome outcome = PublisherOutcome.withArtifactCap(cap);
        lines.forEach(outcome::accept);
        return outcome;
    }

    private static String text(int length) {
        StringBuilder text = new StringBuilder(length);
        for (int i = 0; text.length() < length; i++) {
            text.append("TEST-finding-").append(i).append('\n');
        }
        return text.substring(0, length);
    }

    @Test
    void aTwoHundredKibibyteReviewArtifactArrivesWhole() {
        String answer = text(200 * 1024);

        PublisherOutcome outcome = read(REVIEW_CAP, parts(answer));

        assertEquals(answer, outcome.artifact().orElseThrow(), "the parts join in order, nothing clipped");
        assertFalse(outcome.artifactOverCap());
        assertEquals(List.of(), outcome.handoffRefusals());
    }

    /** Parts may arrive in any order on the stream; their index, not their arrival, orders them. */
    @Test
    void partsAreJoinedByTheirIndex() {
        String answer = text(70 * 1024);
        List<String> lines = new ArrayList<>(parts(answer));
        java.util.Collections.reverse(lines);

        assertEquals(answer, read(REVIEW_CAP, lines).artifact().orElseThrow());
    }

    @Test
    void aMissingPartIsRefused() {
        List<String> lines = new ArrayList<>(parts(text(100 * 1024)));
        lines.remove(1);

        PublisherOutcome outcome = read(REVIEW_CAP, lines);

        assertTrue(outcome.artifact().isEmpty(), "a text with a hole is not a shorter answer");
        assertFalse(outcome.artifactOverCap(), "a gap is a transport fault, not an answer over its cap");
        assertTrue(outcome.handoffRefusals().stream().anyMatch(refusal -> refusal.startsWith("artifact.md:")),
                outcome.handoffRefusals().toString());
    }

    @Test
    void aDuplicatePartIsRefused() {
        List<String> lines = new ArrayList<>(parts(text(100 * 1024)));
        lines.add(part(1, lines.size(), "TEST-replaced"));

        PublisherOutcome outcome = read(REVIEW_CAP, lines);

        assertTrue(outcome.artifact().isEmpty(), "a part sent twice could replace a finding unseen");
        assertTrue(outcome.handoffRefusals().stream().anyMatch(refusal -> refusal.startsWith("artifact.md:")),
                outcome.handoffRefusals().toString());
    }

    /** A part outside its own count, or one naming a different count, cannot belong to the text the others make. */
    @Test
    void aPartThatContradictsTheCountIsRefused() {
        List<String> outside = new ArrayList<>(parts(text(40 * 1024)));
        outside.add(part(9, 2, "TEST-extra"));
        List<String> recounted = new ArrayList<>(parts(text(40 * 1024)));
        recounted.set(1, part(1, recounted.size() + 1, "TEST-recounted"));

        for (List<String> lines : List.of(outside, recounted)) {
            PublisherOutcome outcome = read(REVIEW_CAP, lines);
            assertTrue(outcome.artifact().isEmpty());
            assertTrue(outcome.handoffRefusals().contains("artifact.md:PART_INVALID"), outcome.handoffRefusals().toString());
        }
    }

    /** Review Focus 4: a total over the unit's cap is over cap, so the paid run comes back marked, never lost. */
    @Test
    void aTotalOverTheUnitsCapIsOverCapNotLost() {
        PublisherOutcome outcome = read(REVIEW_CAP, parts(text(REVIEW_CAP + 1)));

        assertTrue(outcome.artifact().isEmpty());
        assertTrue(outcome.artifactOverCap());
    }

    /** A draft keeps its 32 KiB: the parts carry it whole, and one byte more is over its own cap. */
    @Test
    void aDraftArtifactStillArrives() {
        String draft = text(DRAFT_CAP);

        assertEquals(draft, read(DRAFT_CAP, parts(draft)).artifact().orElseThrow());
        assertTrue(read(DRAFT_CAP, parts(text(DRAFT_CAP + 1))).artifactOverCap());
    }

    /** The publisher refusing the file as too large is the same fact: over the unit's cap. */
    @Test
    void aPublisherRefusalForSizeIsOverCap() {
        PublisherOutcome outcome = read(REVIEW_CAP,
                List.of("{\"event\":\"handoff_file_refused\",\"file\":\"artifact.md\",\"why\":\"TOO_LARGE\"}"));

        assertTrue(outcome.artifactOverCap());
        assertTrue(outcome.artifact().isEmpty());
    }
}
