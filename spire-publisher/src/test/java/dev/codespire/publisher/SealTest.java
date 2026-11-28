package dev.codespire.publisher;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.BiPredicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SealTest {

    private static final Seal.Target CANARY = Seal.Target.parse("TEST-canary:1");

    private static final Seal.Target CONTROL = Seal.Target.parse("TEST-control:2");

    private final FakeTime time = new FakeTime();

    /** Rounds finished so far; the control is probed last in a round, so it counts them. */
    private int rounds;

    /** A connector scripted by target and by the 0-based round number. */
    private Seal.Connector scripted(BiPredicate<Seal.Target, Integer> answer) {
        rounds = 0;
        return (target, timeout) -> {
            boolean connects = answer.test(target, rounds);
            if (target.equals(CONTROL)) {
                rounds++;
            }
            return connects;
        };
    }

    private Seal.Result run(Seal.Connector connector) throws InterruptedException {
        Seal.Settings settings = new Seal.Settings(List.of(CANARY), CONTROL, 5, Duration.ofMillis(500),
                Duration.ofMillis(250), Duration.ofSeconds(60));
        return new Seal(settings, connector, time, time).run();
    }

    @Test
    void fiveBlockedRoundsInARowWithTheControlAnsweringSeal() throws Exception {
        Seal.Connector connector = scripted((target, round) -> target.equals(CONTROL));

        assertEquals(new Seal.Result.Sealed(), run(connector));
        assertEquals(5, rounds);
    }

    @Test
    void anOpenRoundRestartsTheCount() throws Exception {
        Seal.Connector connector = scripted((target, round) -> target.equals(CONTROL) || round == 4);

        assertEquals(new Seal.Result.Sealed(), run(connector));
        assertEquals(10, rounds, "four blocked, one open, then five blocked rounds");
    }

    @Test
    void aCanaryStillOpenAtTheDeadlineIsNamed() throws Exception {
        Seal.Connector connector = scripted((target, round) -> true);

        assertEquals(new Seal.Result.Open(CANARY), run(connector));
        assertFalse(time.instant().isBefore(Instant.EPOCH.plusSeconds(60)), "waited out the deadline");
    }

    @Test
    void aControlThatNeverAnswersIsNotASeal() throws Exception {
        Seal.Connector connector = scripted((target, round) -> false);

        assertEquals(new Seal.Result.NoControl(CONTROL), run(connector));
    }

    @Test
    void aRefusedConnectIsBlockedAndOnlyACompletedConnectIsOpen() throws Exception {
        Seal.SocketConnector real = new Seal.SocketConnector();
        int closedPort;
        try (ServerSocket probe = new ServerSocket(0)) {
            closedPort = probe.getLocalPort();
        }
        assertFalse(real.connects(new Seal.Target("127.0.0.1", closedPort), Duration.ofSeconds(2)));
        try (ServerSocket listening = new ServerSocket(0)) {
            assertTrue(real.connects(new Seal.Target("127.0.0.1", listening.getLocalPort()), Duration.ofSeconds(2)));
        }
    }

    @Test
    void aSealWithNoCanaryIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> new Seal.Settings(List.of(), CONTROL, 5,
                Duration.ofMillis(500), Duration.ofMillis(250), Duration.ofSeconds(60)));
    }

    @Test
    void theCommandMapsResultsToExitCodes() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        PrintStream err = new PrintStream(new ByteArrayOutputStream());

        int open = SealMain.run(new String[] {"--canary", "192.0.2.1:1"}, out, err, (t, d) -> true, time, time);
        assertEquals(3, open);
        assertEquals("{\"sealed\":false,\"open\":\"192.0.2.1:1\"}", bytes.toString(StandardCharsets.UTF_8).trim());

        int bad = SealMain.run(new String[] {"--bogus", "x"}, out, err, (t, d) -> true, time, time);
        assertEquals(2, bad);
    }

    @Test
    void tooFewSealedRoundsAtTheDeadlineIsUnprovenNotOpen() throws Exception {
        Seal.Settings settings = new Seal.Settings(List.of(CANARY), CONTROL, 5, Duration.ofMillis(500),
                Duration.ofMillis(500), Duration.ofSeconds(1));
        Seal.Connector blockedAndAnswering = (target, timeout) -> target.equals(CONTROL);

        assertEquals(new Seal.Result.Unproven(3), new Seal(settings, blockedAndAnswering, time, time).run());

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int code = SealMain.run(new String[] {"--canary", "192.0.2.1:1", "--control", "TEST-control:2",
                "--interval-ms", "500", "--deadline-s", "1"}, new PrintStream(bytes, true, StandardCharsets.UTF_8),
                new PrintStream(new ByteArrayOutputStream()), blockedAndAnswering, new FakeTime(), new FakeTime());
        assertEquals(3, code);
        assertEquals("{\"sealed\":false,\"rounds\":3}", bytes.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void aCanaryThatIsAHostNameIsRefusedWithExitTwo() {
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = SealMain.run(new String[] {"--canary", "TEST-canary:1"},
                new PrintStream(new ByteArrayOutputStream()), new PrintStream(err, true, StandardCharsets.UTF_8),
                (t, d) -> false, time, time);

        assertEquals(2, code);
        assertTrue(err.toString(StandardCharsets.UTF_8).contains("IP address"), err.toString());
    }
}
