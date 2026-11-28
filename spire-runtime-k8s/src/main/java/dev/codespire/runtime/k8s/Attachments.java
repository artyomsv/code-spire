package dev.codespire.runtime.k8s;

import java.io.InputStream;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The log streams {@code attach} holds open, by run id, so that {@code cancel} and {@code destroy} can end them.
 *
 * <p>A blocking read of a log stream ignores a thread interrupt, and on a real cluster a close of the fabric8 log
 * watch does not end it either (measured on kind: the read went on past the pod's stop). The worker interrupts its
 * readers when a salvage fails, and a reader left blocked would hold its thread for the life of the process. So the
 * read runs on its own virtual thread, and the caller returns as soon as the stream ends, it is closed through
 * {@link #closeAll}, or the caller is interrupted. A line the reader still gets after that is dropped, so the
 * caller's consumer is never called once {@code follow} has returned for a close.
 */
final class Attachments {

    private final ConcurrentHashMap<String, Set<Attachment>> open = new ConcurrentHashMap<>();

    /**
     * Delivers the lines of {@code log} until it ends, it is closed through {@link #closeAll}, or the calling thread
     * is interrupted. {@code close} closes the stream; it runs at most once, on its own thread, so neither a cancel
     * nor an interrupted caller waits for it.
     */
    void follow(String runId, InputStream log, Runnable close, Consumer<String> lines) {
        Attachment attachment = new Attachment(close);
        open.compute(runId, (id, held) -> {
            Set<Attachment> set = held == null ? ConcurrentHashMap.newKeySet() : held;
            set.add(attachment);
            return set;
        });
        try {
            read(log, lines, attachment);
        } finally {
            open.computeIfPresent(runId, (id, held) -> {
                held.remove(attachment);
                return held.isEmpty() ? null : held;
            });
        }
    }

    /** Ends every open {@link #follow} of the run. */
    void closeAll(String runId) {
        Set<Attachment> held = open.remove(runId);
        if (held == null) {
            return;
        }
        for (Attachment attachment : held) {
            attachment.close();
        }
    }

    int openCount(String runId) {
        Set<Attachment> held = open.get(runId);
        return held == null ? 0 : held.size();
    }

    private static void read(InputStream log, Consumer<String> lines, Attachment attachment) {
        AtomicReference<RuntimeException> fault = new AtomicReference<>();
        Thread.ofVirtual().name("spire-attach").start(() -> {
            try {
                PodLogs.follow(log, line -> {
                    if (!attachment.isClosed()) {
                        lines.accept(line);
                    }
                });
            } catch (RuntimeException e) {
                fault.set(e);
            } finally {
                attachment.ended();
            }
        });
        try {
            attachment.awaitEnd();
        } catch (InterruptedException interrupted) {
            attachment.close();
            Thread.currentThread().interrupt();
            return;
        }
        RuntimeException failed = fault.get();
        // A stream closed on purpose may end its read with an error of the transport; that is no fault of the run.
        if (failed != null && !attachment.isClosed()) {
            throw failed;
        }
    }

    private static final class Attachment {

        private final Runnable close;

        private final AtomicBoolean closed = new AtomicBoolean();

        /** Counted down when the read ends or the attachment is closed, whichever is first. */
        private final CountDownLatch done = new CountDownLatch(1);

        Attachment(Runnable close) {
            this.close = close;
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                done.countDown();
                Thread.ofVirtual().name("spire-attach-close").start(this::runClose);
            }
        }

        private void runClose() {
            try {
                close.run();
            } catch (RuntimeException ignored) {
                // A stream that fails to close is a stream already broken; the caller has returned either way.
            }
        }

        void ended() {
            done.countDown();
        }

        void awaitEnd() throws InterruptedException {
            done.await();
        }

        boolean isClosed() {
            return closed.get();
        }
    }
}
