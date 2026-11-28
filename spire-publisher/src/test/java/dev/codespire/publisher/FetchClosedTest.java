package dev.codespire.publisher;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FetchClosedTest {

    private static final Seal.Target CLOSE = Seal.Target.parse("TEST-close:1");

    private static final Seal.Target TARGET = Seal.Target.parse("TEST-target:2");

    private final FakeTime time = new FakeTime();

    private boolean run(Seal.Connector connector) throws InterruptedException {
        FetchClosed.Settings settings = new FetchClosed.Settings(CLOSE, TARGET, 3, Duration.ofMillis(500),
                Duration.ofMillis(250), Duration.ofSeconds(30));
        return new FetchClosed(settings, connector, time, time).run();
    }

    @Test
    void itSendsTheCloseOnceThenNeedsThreeRefusalsInARow() throws Exception {
        int[] closeCalls = {0};
        int[] targetCalls = {0};
        Seal.Connector connector = (target, timeout) -> {
            if (target.equals(CLOSE)) {
                closeCalls[0]++;
                return true;
            }
            targetCalls[0]++;
            return targetCalls[0] == 2;
        };

        assertEquals(true, run(connector));
        assertEquals(1, closeCalls[0], "the close is sent once");
        assertEquals(5, targetCalls[0], "refused, open, then three refusals");
    }

    @Test
    void aProxyThatIgnoresTheCloseFailsAtTheDeadline() throws Exception {
        assertEquals(false, run((target, timeout) -> true));

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        FakeTime other = new FakeTime();
        int code = FetchClosedMain.run(new String[] {"--close", "TEST-close:1", "--target", "TEST-target:2"}, out,
                new PrintStream(new ByteArrayOutputStream()), (target, timeout) -> true, other, other);
        assertEquals(4, code);
        assertEquals("{\"fetchClosed\":false}", bytes.toString(StandardCharsets.UTF_8).trim());
    }

    @Test
    void aCloseTargetThatAlreadyRefusesIsFine() throws Exception {
        assertEquals(true, run((target, timeout) -> false));
    }
}
