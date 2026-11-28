package dev.codespire.runtime.k8s;

import java.io.InputStream;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * The log streams {@code attach} holds open, by run id, so that {@code cancel} and {@code destroy} can end them.
 *
 * <p>A blocking read of a log stream ignores a thread interrupt. The worker interrupts its readers when a salvage
 * fails, and a reader left blocked on a unit that still runs would hold its thread for the life of the process. So
 * the read runs on its own virtual thread and the caller waits for it: an interrupt of the caller closes the stream,
 * and a close by {@code cancel} or {@code destroy} ends the read with no error.
 */
final class Attachments {

    /** How long an interrupted attach waits for its reader to see the close. */
    static final Duration CLOSE_WAIT = Duration.ofSeconds(5);

    private final ConcurrentHashMap<String, Set<Attachment>> open = new ConcurrentHashMap<>();

    /**
     * Delivers the lines of {@code log} until it ends, it is closed through {@link #closeAll}, or the calling thread
     * is interrupted. {@code close} closes the stream; it is called at most once.
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
        Thread reader = Thread.ofVirtual().name("spire-attach").start(() -> {
            try {
                PodLogs.follow(log, lines);
            } catch (RuntimeException e) {
                fault.set(e);
            }
        });
        try {
            reader.join();
        } catch (InterruptedException interrupted) {
            attachment.close();
            awaitEnd(reader);
            Thread.currentThread().interrupt();
            return;
        }
        RuntimeException failed = fault.get();
        // A stream closed on purpose ends its read with an error of the transport; that is no fault of the run.
        if (failed != null && !attachment.isClosed()) {
            throw failed;
        }
    }

    /** Bounded: a stream whose close does not end the read must not hold the caller after its interrupt. */
    private static void awaitEnd(Thread reader) {
        try {
            reader.join(CLOSE_WAIT);
        } catch (InterruptedException again) {
            // The caller is leaving anyway; its interrupt flag is set again by the caller of this method.
        }
    }

    private static final class Attachment {

        private final Runnable close;

        private final AtomicBoolean closed = new AtomicBoolean();

        Attachment(Runnable close) {
            this.close = close;
        }

        void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    close.run();
                } catch (RuntimeException ignored) {
                    // A stream that fails to close is a stream already broken; the read ends either way.
                }
            }
        }

        boolean isClosed() {
            return closed.get();
        }
    }
}
