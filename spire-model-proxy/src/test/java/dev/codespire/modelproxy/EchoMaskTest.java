package dev.codespire.modelproxy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EchoMaskTest {

    private static final String SECRET = "TEST-secret-key";
    private static final String ACCOUNT = "TEST-account";
    private static final String SHORT = "TEST-PREFIX";
    private static final String LONG = "TEST-PREFIX-LONG";
    private static final String W = EchoMask.WITHHELD;

    private static EchoMask mask() {
        return new EchoMask(List.of(bytes(SECRET), bytes(ACCOUNT)));
    }

    @Test
    void everyEchoIsWithheld() {
        assertEquals("a spire-model-withheld b spire-model-withheld c spire-model-withheld",
                all(mask(), "a " + SECRET + " b " + ACCOUNT + " c " + SECRET));
    }

    /** At every split point of the body, the echo is caught and nothing else is changed. */
    @Test
    void anEchoSplitAnywhereIsCaught() {
        String body = "data: Bearer " + SECRET + " done";
        for (int split = 0; split <= body.length(); split++) {
            EchoMask mask = mask();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            out.writeBytes(next(mask, body.substring(0, split)));
            out.writeBytes(next(mask, body.substring(split)));
            out.writeBytes(mask.finish());

            assertEquals("data: Bearer spire-model-withheld done", out.toString(StandardCharsets.UTF_8),
                    "split at " + split);
        }
    }

    /** Only a tail that may begin an echo waits; the rest is passed at once. */
    @Test
    void onlyThePossibleStartOfAnEchoIsHeld() {
        EchoMask mask = mask();

        assertEquals("data: x\n\n", text(next(mask, "data: x\n\n")));
        assertEquals("data: ", text(next(mask, "data: TEST-sec")));
        assertEquals("TEST-sec and more", text(next(mask, " and more")));
    }

    @Test
    void aHeldStartThatIsNoEchoIsReleased() {
        EchoMask mask = mask();

        assertEquals("x ", text(next(mask, "x TEST-")));
        assertEquals("TEST-other", text(next(mask, "other")));
    }

    @Test
    void theEndReleasesWhatWasHeld() {
        EchoMask mask = mask();

        assertEquals("tail ", text(next(mask, "tail TEST-secret-ke")));
        assertEquals("TEST-secret-ke", text(mask.finish()));
    }

    @Test
    void anEmptyValueIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new EchoMask(List.of(new byte[0])));
    }

    /** Two echoes with nothing between them are two placeholders, however the body is cut. */
    @Test
    void adjacentEchoesAreEachWithheldUnderEveryPartition() {
        List<byte[]> values = List.of(bytes(SECRET), bytes(ACCOUNT));

        assertEveryPartition(values, "x" + SECRET + SECRET + "y", "x" + W + W + "y");
        assertEveryPartition(values, "x" + SECRET + ACCOUNT + "y", "x" + W + W + "y");
        assertEveryPartition(values, "x" + ACCOUNT + SECRET + "y", "x" + W + W + "y");
        assertEveryPartition(values, SECRET + ACCOUNT + SECRET, W + W + W);
    }

    /** A value that extends a shorter one is withheld whole, in either configured order. */
    @Test
    void theLongestValueThatMatchesIsWithheld() {
        for (List<byte[]> values : prefixOrders()) {
            assertEveryPartition(values, "a " + LONG + " b", "a " + W + " b");
            assertEveryPartition(values, "a " + SHORT + " b", "a " + W + " b");
            assertEveryPartition(values, SHORT + LONG, W + W);
            assertEveryPartition(values, LONG + SHORT, W + W);
            assertEveryPartition(values, "a " + LONG, "a " + W);
            assertEveryPartition(values, "a " + SHORT, "a " + W);
            assertEveryPartition(values, "a " + SHORT + "-LON", "a " + W + "-LON");
        }
    }

    /** A complete shorter value is still held while the longer one may be arriving. */
    @Test
    void aCompleteShorterValueWaitsForTheLongerOne() {
        for (List<byte[]> values : prefixOrders()) {
            EchoMask mask = new EchoMask(values);

            assertEquals("a ", text(next(mask, "a " + SHORT)));
            assertEquals("", text(next(mask, "-LO")));
            assertEquals(W + " b", text(next(mask, "NG b")));
            assertEquals("", text(mask.finish()));
        }
    }

    private static List<List<byte[]>> prefixOrders() {
        return List.of(List.of(bytes(SHORT), bytes(LONG)), List.of(bytes(LONG), bytes(SHORT)));
    }

    /**
     * The output is the expected string for the body in one chunk, at every two-way and three-way split, and in
     * fixed-size chunks of every size.
     */
    private static void assertEveryPartition(List<byte[]> values, String body, String expected) {
        assertEquals(expected, run(values, List.of(body)), "one chunk: " + body);
        for (int first = 0; first <= body.length(); first++) {
            for (int second = first; second <= body.length(); second++) {
                List<String> chunks = List.of(body.substring(0, first), body.substring(first, second),
                        body.substring(second));
                assertEquals(expected, run(values, chunks), "chunks " + chunks);
            }
        }
        for (int size = 1; size <= body.length(); size++) {
            List<String> chunks = new ArrayList<>();
            for (int at = 0; at < body.length(); at += size) {
                chunks.add(body.substring(at, Math.min(body.length(), at + size)));
            }
            assertEquals(expected, run(values, chunks), "chunks " + chunks);
        }
    }

    private static String run(List<byte[]> values, List<String> chunks) {
        EchoMask mask = new EchoMask(values);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        chunks.forEach(chunk -> out.writeBytes(next(mask, chunk)));
        out.writeBytes(mask.finish());
        return out.toString(StandardCharsets.UTF_8);
    }

    private static String all(EchoMask mask, String body) {
        return text(next(mask, body)) + text(mask.finish());
    }

    private static byte[] next(EchoMask mask, String text) {
        byte[] chunk = bytes(text);
        return mask.next(chunk, chunk.length);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
