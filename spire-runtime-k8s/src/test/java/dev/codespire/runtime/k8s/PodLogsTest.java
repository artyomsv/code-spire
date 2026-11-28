package dev.codespire.runtime.k8s;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** A container's log stream reaches the caller as whole lines, bounded, whatever chunks the API server sends. */
class PodLogsTest {

    private static List<String> follow(InputStream log) {
        List<String> lines = new ArrayList<>();
        PodLogs.follow(log, lines::add);
        return lines;
    }

    private static InputStream text(String text) {
        return new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8));
    }

    /** Two streams read one after the other: a read never crosses from the first into the second. */
    private static InputStream twoReads(String first, String second) {
        return new SequenceInputStream(text(first), text(second));
    }

    @Test
    void aLineSplitAcrossTwoReadsArrivesWhole() {
        List<String> lines = follow(twoReads("{\"event\":\"art", "ifact\",\"text\":\"TEST\"}\nTEST-next\n"));

        assertEquals(List.of("{\"event\":\"artifact\",\"text\":\"TEST\"}", "TEST-next"), lines);
    }

    @Test
    void aLineOverSixtyFourKibibytesIsCutOnceAndTheNextLineStillArrives() {
        String longLine = "x".repeat(PodLogs.MAX_LINE_CHARS * 3);

        List<String> lines = follow(twoReads(longLine, "TEST-tail-of-the-long-line\nTEST-next\n"));

        assertEquals(3, lines.size(), "the cut line, the marker, the next line: " + lines.size());
        assertEquals("x".repeat(PodLogs.MAX_LINE_CHARS), lines.get(0));
        assertEquals(PodLogs.CUT_MARKER, lines.get(1));
        assertEquals("TEST-next", lines.get(2));
    }

    @Test
    void aLastLineWithNoNewlineArrivesAtTheEnd() {
        assertEquals(List.of("TEST-a", "TEST-last"), follow(text("TEST-a\nTEST-last")));
    }

    @Test
    void carriageReturnsAreNotPartOfALine() {
        assertEquals(List.of("TEST-a", "TEST-b"), follow(twoReads("TEST-a\r", "\nTEST-b\r\n")));
    }

    @Test
    void aCharacterSplitAcrossTwoReadsArrivesWhole() {
        byte[] euro = "TEST-€\n".getBytes(StandardCharsets.UTF_8);
        InputStream split = new SequenceInputStream(new ByteArrayInputStream(euro, 0, 6),
                new ByteArrayInputStream(euro, 6, euro.length - 6));

        assertEquals(List.of("TEST-€"), follow(split));
    }

    @Test
    void emptyLinesAreSkippedAsOnDocker() {
        assertEquals(List.of("TEST-a", "TEST-b"), follow(text("\nTEST-a\n\n\r\nTEST-b\n")));
    }
}
