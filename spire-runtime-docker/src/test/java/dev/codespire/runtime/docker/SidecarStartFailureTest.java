package dev.codespire.runtime.docker;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Why a sidecar did not come up, as the run's failure detail says it (live acceptance of harness reviews step 3: a
 * model proxy that was running yet not seen listening was reported as "did not listen within PT30S" and nothing else).
 */
class SidecarStartFailureTest {

    private static String joined(List<String> tail) {
        return String.join(SidecarStartFailure.SEPARATOR, tail);
    }

    @Test
    void theDetailNamesTheStateTheExitCodeAndOomKilled() {
        String detail = SidecarStartFailure.detail("the model proxy did not listen within PT2M",
                new SidecarStartFailure.State("running", 0L, false), List.of());

        assertTrue(detail.startsWith("the model proxy did not listen within PT2M"), detail);
        assertTrue(detail.contains("state running"), detail);
        assertTrue(detail.contains("exit 0"), detail);
        assertTrue(detail.contains("OOMKilled false"), detail);
        assertTrue(detail.contains("last output: none"), detail);
    }

    @Test
    void theDetailQuotesTheLastOutput() {
        String detail = SidecarStartFailure.detail("the model proxy exited before it listened",
                new SidecarStartFailure.State("exited", 2L, true),
                List.of("spire-model: SPIRE_MODEL_SECRET is not set"));

        assertTrue(detail.contains("state exited"), detail);
        assertTrue(detail.contains("exit 2"), detail);
        assertTrue(detail.contains("OOMKilled true"), detail);
        assertTrue(detail.endsWith("last output: spire-model: SPIRE_MODEL_SECRET is not set"), detail);
    }

    @Test
    void anUnknownStateIsSaidToBeUnknown() {
        String detail = SidecarStartFailure.detail("TEST",
                new SidecarStartFailure.State(null, null, null), List.of());

        assertTrue(detail.contains("state unknown, exit unknown, OOMKilled unknown"), detail);
    }

    @Test
    void theTailKeepsOnlyTheLastLines() {
        List<String> log = IntStream.range(0, 50).mapToObj(line -> "TEST-line-" + line).toList();

        List<String> tail = SidecarStartFailure.tail(log);

        assertEquals(SidecarStartFailure.TAIL_LINES, tail.size(), tail.toString());
        assertEquals("TEST-line-30", tail.getFirst());
        assertEquals("TEST-line-49", tail.getLast());
    }

    @Test
    void theTailIsBoundedInCharactersAndKeepsTheNewestLineWhole() {
        List<String> log = IntStream.range(0, 20).mapToObj(line -> "x".repeat(495) + "TEST-" + line).toList();

        List<String> tail = SidecarStartFailure.tail(log);

        assertTrue(joined(tail).length() <= SidecarStartFailure.TAIL_CHARS, "length " + joined(tail).length());
        assertEquals(log.getLast(), tail.getLast());
        assertTrue(tail.size() > 1, "as many whole lines as fit: " + tail.size());
    }

    @Test
    void aSingleLineLongerThanTheBoundKeepsItsEnd() {
        List<String> tail = SidecarStartFailure.tail(List.of("TEST-older", "y".repeat(5000) + "TEST-end"));

        assertEquals(1, tail.size(), "only the newest line, cut");
        assertTrue(joined(tail).length() <= SidecarStartFailure.TAIL_CHARS, "length " + joined(tail).length());
        assertTrue(tail.getFirst().endsWith("TEST-end"), "its end names the failure");
    }

    @Test
    void aShortLogIsKeptWhole() {
        assertEquals(List.of("TEST-a", "TEST-b"), SidecarStartFailure.tail(List.of("TEST-a", "TEST-b")));
    }

    /** Live acceptance: once a JVM start under 0.5 CPU on a busy host was not seen listening within 30 s. */
    @Test
    void theModelProxyMayTakeTwoMinutesToListen() {
        assertEquals(Duration.ofSeconds(120), ModelProxyContainer.START_TIMEOUT);
    }
}
