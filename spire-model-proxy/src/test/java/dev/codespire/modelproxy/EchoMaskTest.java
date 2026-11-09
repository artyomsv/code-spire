package dev.codespire.modelproxy;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class EchoMaskTest {

    private static final String SECRET = "TEST-secret-key";
    private static final String ACCOUNT = "TEST-account";

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
