package dev.codespire.runtime.k8s;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How {@code attach}'s follow ends: at the end of the stream, on a close by cancel or destroy, on an interrupt. */
class AttachmentsTest {

    private static final String RUN = "TEST-run-attach";

    private static final Duration PROMPTLY = Duration.ofSeconds(3);

    /** A log stream that blocks every read until it is closed, as a followed log of a running container does. */
    private static class BlockingLog extends InputStream {

        final CountDownLatch closed = new CountDownLatch(1);

        @Override
        public int read() throws IOException {
            try {
                closed.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IOException("interrupted", e);
            }
            throw new IOException("the stream was closed");
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    /**
     * A stream whose close does not end a read in progress, as the fabric8 log watch measured on kind. The test
     * releases it at the end, so its reader thread does not outlive the test.
     */
    private static final class DeafLog extends BlockingLog {

        @Override
        public void close() {
        }

        void release() {
            closed.countDown();
        }
    }

    /** Runs a follow on its own thread and records how it ended. */
    private static final class Follower {

        final Thread thread;

        final AtomicReference<RuntimeException> thrown = new AtomicReference<>();

        final AtomicBoolean interruptedAfter = new AtomicBoolean();

        Follower(Attachments attachments, BlockingLog log) {
            thread = Thread.ofPlatform().start(() -> {
                try {
                    attachments.follow(RUN, log, log::close, line -> { });
                } catch (RuntimeException e) {
                    thrown.set(e);
                }
                interruptedAfter.set(Thread.currentThread().isInterrupted());
            });
        }

        boolean endsPromptly() throws InterruptedException {
            return thread.join(PROMPTLY);
        }
    }

    private static void awaitOpen(Attachments attachments) throws InterruptedException {
        long deadline = System.nanoTime() + PROMPTLY.toNanos();
        while (attachments.openCount(RUN) == 0) {
            if (System.nanoTime() > deadline) {
                throw new AssertionError("the follow never registered");
            }
            TimeUnit.MILLISECONDS.sleep(20);
        }
    }

    @Test
    void everyLineArrivesAndTheFollowEndsWithTheStream() {
        List<String> lines = new CopyOnWriteArrayList<>();
        InputStream log = new ByteArrayInputStream("TEST-one\nTEST-two\n".getBytes(StandardCharsets.UTF_8));

        new Attachments().follow(RUN, log, () -> { }, lines::add);

        assertEquals(List.of("TEST-one", "TEST-two"), lines);
    }

    @Test
    void anInterruptEndsTheFollowAndKeepsTheFlag() throws InterruptedException {
        Attachments attachments = new Attachments();
        Follower follower = new Follower(attachments, new BlockingLog());
        awaitOpen(attachments);

        follower.thread.interrupt();

        assertTrue(follower.endsPromptly(), "a blocked follow ignored the interrupt of its thread");
        assertNull(follower.thrown.get(), "an interrupt is no read fault");
        assertTrue(follower.interruptedAfter.get(), "the caller's interrupt flag was lost");
    }

    @Test
    void closeAllEndsTheFollowWithNoError() throws InterruptedException {
        Attachments attachments = new Attachments();
        Follower follower = new Follower(attachments, new BlockingLog());
        awaitOpen(attachments);

        attachments.closeAll(RUN);

        assertTrue(follower.endsPromptly(), "cancel or destroy did not end the follow");
        assertNull(follower.thrown.get(), "a stream closed on purpose is no read fault");
        assertFalse(follower.interruptedAfter.get());
        assertEquals(0, attachments.openCount(RUN));
    }

    @Test
    void aCloseEndsTheFollowEvenWhenTheStreamKeepsReading() throws InterruptedException {
        Attachments attachments = new Attachments();
        DeafLog log = new DeafLog();
        Follower follower = new Follower(attachments, log);
        try {
            awaitOpen(attachments);

            attachments.closeAll(RUN);

            assertTrue(follower.endsPromptly(), "the follow waited for a read its close could not end");
            assertNull(follower.thrown.get());
        } finally {
            log.release();
        }
    }

    @Test
    void anInterruptEndsTheFollowEvenWhenTheStreamKeepsReading() throws InterruptedException {
        Attachments attachments = new Attachments();
        DeafLog log = new DeafLog();
        Follower follower = new Follower(attachments, log);
        try {
            awaitOpen(attachments);

            follower.thread.interrupt();

            assertTrue(follower.endsPromptly(), "the follow waited for a read its close could not end");
            assertTrue(follower.interruptedAfter.get(), "the caller's interrupt flag was lost");
        } finally {
            log.release();
        }
    }

    @Test
    void aLineReadAfterTheCloseNeverReachesTheConsumer() throws InterruptedException {
        Attachments attachments = new Attachments();
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch readerDone = new CountDownLatch(1);
        InputStream late = new InputStream() {
            private final InputStream rest = new ByteArrayInputStream("TEST-late
".getBytes(StandardCharsets.UTF_8));

            @Override
            public int read() throws IOException {
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", e);
                }
                int next = rest.read();
                if (next < 0) {
                    readerDone.countDown();
                }
                return next;
            }
        };
        List<String> lines = new CopyOnWriteArrayList<>();
        Thread follower = Thread.ofPlatform().start(() -> attachments.follow(RUN, late, () -> { }, lines::add));
        awaitOpen(attachments);

        attachments.closeAll(RUN);
        assertTrue(follower.join(PROMPTLY), "the follow did not return on close");
        release.countDown();

        assertTrue(readerDone.await(PROMPTLY.toSeconds(), TimeUnit.SECONDS), "the reader never read the late line");
        assertEquals(List.of(), lines, "a line reached the consumer after follow returned");
    }

    @Test
    void aReadFaultNobodyCausedReachesTheCaller() {
        InputStream broken = new InputStream() {
            @Override
            public int read() throws IOException {
                throw new IOException("TEST broken stream");
            }
        };
        AtomicReference<RuntimeException> thrown = new AtomicReference<>();

        try {
            new Attachments().follow(RUN, broken, () -> { }, line -> { });
        } catch (RuntimeException e) {
            thrown.set(e);
        }

        assertInstanceOf(UncheckedIOException.class, thrown.get());
    }
}
