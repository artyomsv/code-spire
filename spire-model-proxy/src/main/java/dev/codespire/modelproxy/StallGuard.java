package dev.codespire.modelproxy;

import java.io.Closeable;
import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Ends a vendor's response body that stalls or never ends: once {@link Limits#idleRead()} passes without a byte, or
 * {@link Limits#totalResponse()} passes in all, it closes the body, and the read waiting on it fails.
 *
 * <p>The JDK client has no read timeout of its own for a streamed body. Closing its stream is what unblocks a reader
 * there: the close queues the end of the body, and the read then throws because the stream was closed.
 */
final class StallGuard implements AutoCloseable {

    private static final long SHORTEST_CHECK_NANOS = TimeUnit.MILLISECONDS.toNanos(50);
    private static final long LONGEST_CHECK_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final Closeable body;
    private final long idleNanos;
    private final long deadlineNanos;
    private volatile long lastProgressNanos;
    private ScheduledFuture<?> check;

    private StallGuard(Closeable body, Limits limits) {
        this.body = body;
        long now = System.nanoTime();
        this.idleNanos = limits.idleRead().toNanos();
        this.deadlineNanos = now + limits.totalResponse().toNanos();
        this.lastProgressNanos = now;
    }

    /** Starts watching {@code body}; {@link #close} stops. */
    static StallGuard watch(Closeable body, Limits limits, ScheduledExecutorService timer) {
        StallGuard guard = new StallGuard(body, limits);
        long period = checkPeriod(limits);
        guard.check = timer.scheduleAtFixedRate(guard::checkNow, period, period, TimeUnit.NANOSECONDS);
        return guard;
    }

    /** A byte arrived. */
    void progressed() {
        lastProgressNanos = System.nanoTime();
    }

    @Override
    public void close() {
        check.cancel(false);
    }

    private void checkNow() {
        long now = System.nanoTime();
        if (now - lastProgressNanos < idleNanos && now - deadlineNanos < 0) {
            return;
        }
        // Closing twice is harmless, so a check that runs again before the reader's close() cancels it does no harm.
        try {
            body.close();
        } catch (IOException alreadyGone) {
            // The body is closed either way; the reader sees it end.
        }
    }

    /** A tenth of the shorter bound, so an expiry is seen within a tenth of it, between 50 ms and 1 s. */
    private static long checkPeriod(Limits limits) {
        Duration shorter = limits.idleRead().compareTo(limits.totalResponse()) < 0
                ? limits.idleRead() : limits.totalResponse();
        return Math.clamp(shorter.toNanos() / 10, SHORTEST_CHECK_NANOS, LONGEST_CHECK_NANOS);
    }
}
