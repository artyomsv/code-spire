package dev.codespire.publisher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Stay mode: a Kubernetes native sidecar must outlive DONE until TERM, so it is never restarted. */
class ReadOnlyPublisherStayTest {

    private static final String ARTIFACT_LINE = "{\"event\":\"artifact\",\"text\":\"TEST-spec\"}";

    @TempDir
    Path root;

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private final PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);

    private Path handoff() throws Exception {
        Path handoff = Files.createDirectory(root.resolve("handoff"));
        Files.writeString(handoff.resolve(HandoffFiles.ARTIFACT), "TEST-spec");
        return handoff;
    }

    private void done(Path handoff) throws Exception {
        Files.writeString(handoff.resolve(PublisherMain.DONE_SENTINEL), "");
    }

    private Thread start(Path handoff, CountDownLatch term, AtomicInteger exit) {
        Thread thread = new Thread(() -> exit.set(
                ReadOnlyPublisherMain.run(Map.of("SPIRE_HANDOFF_DIR", handoff.toString()), out, term)));
        thread.start();
        return thread;
    }

    private String lines() {
        return output.toString(StandardCharsets.UTF_8);
    }

    private static int count(String text, String part) {
        return text.split(Pattern.quote(part), -1).length - 1;
    }

    @Test
    void itPrintsTheArtifactOnceThenWaitsForTerm() throws Exception {
        Path handoff = handoff();
        done(handoff);
        CountDownLatch term = new CountDownLatch(1);
        AtomicInteger exit = new AtomicInteger(-1);

        Thread thread = start(handoff, term, exit);
        thread.join(1_500);

        assertTrue(thread.isAlive(), "stay mode must not return at DONE");
        assertEquals(1, count(lines(), ARTIFACT_LINE), lines());
        term.countDown();
        thread.join(5_000);
        assertFalse(thread.isAlive());
        assertEquals(0, exit.get());
        assertEquals(1, count(lines(), ARTIFACT_LINE), lines());
    }

    @Test
    void termRightAfterDoneStillPrintsTheWholeArtifact() throws Exception {
        Path handoff = handoff();
        CountDownLatch term = new CountDownLatch(1);
        AtomicInteger exit = new AtomicInteger(-1);

        Thread thread = start(handoff, term, exit);
        while (thread.getState() != Thread.State.TIMED_WAITING) {
            Thread.sleep(10);
        }
        done(handoff);
        term.countDown();
        thread.join(5_000);

        assertEquals(0, exit.get(), lines());
        assertEquals(1, count(lines(), ARTIFACT_LINE), lines());
    }

    @Test
    void termWithNoDoneExitsSeventyFive() throws Exception {
        CountDownLatch term = new CountDownLatch(1);
        term.countDown();

        int exitCode = ReadOnlyPublisherMain.run(Map.of("SPIRE_HANDOFF_DIR", handoff().toString()), out, term);

        assertEquals(75, exitCode);
        assertEquals(ReadOnlyPublisher.TERM_BEFORE_DONE, exitCode);
        assertFalse(lines().contains("\"event\":\"artifact\""), lines());
    }

    @Test
    void withoutTheFlagItReturnsAtDoneAsBefore() throws Exception {
        Path handoff = handoff();
        done(handoff);

        int exitCode = ReadOnlyPublisherMain.run(Map.of("SPIRE_HANDOFF_DIR", handoff.toString()), out);

        assertEquals(0, exitCode);
        assertEquals(1, count(lines(), ARTIFACT_LINE), lines());
    }
}
