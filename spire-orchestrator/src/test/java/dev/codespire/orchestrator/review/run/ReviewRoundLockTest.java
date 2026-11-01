package dev.codespire.orchestrator.review.run;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The review's round lock on the real database (review of PR #368, code-quality/H-1): while a harness outcome holds it,
 * a forced re-run of the same review waits, and another review's does not. A unit test's fake lock cannot show that the
 * advisory lock actually blocks; this one can.
 */
@QuarkusTest
class ReviewRoundLockTest {

    private static final long WAIT_SECONDS = 10;

    /** How long a blocked holder is given to get in anyway; it is a negative check, so a short wait cannot fail falsely. */
    private static final long BLOCKED_MILLIS = 500;

    @Inject
    ReviewRoundLock lock;

    private final ExecutorService threads = Executors.newFixedThreadPool(2);

    private final String reviewId = "review::TEST-ROUND-LOCK/TEST-REPO#" + UUID.randomUUID();

    @AfterEach
    void stop() {
        threads.shutdownNow();
    }

    @Test
    void aSecondHolderOfTheSameReviewWaitsForTheFirst() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> first = holdUntil(reviewId, release);
        CompletableFuture<String> second = CompletableFuture.supplyAsync(() -> lock.holding(reviewId, () -> "second"),
                threads);

        assertThrows(TimeoutException.class, () -> second.get(BLOCKED_MILLIS, TimeUnit.MILLISECONDS),
                "a re-run must wait while an outcome holds the review's round lock");
        release.countDown();

        assertEquals("first", first.get(WAIT_SECONDS, TimeUnit.SECONDS));
        assertEquals("second", second.get(WAIT_SECONDS, TimeUnit.SECONDS), "and gets in once the outcome commits");
    }

    /** The control: one review's outcome never holds up another review's re-run. */
    @Test
    void anotherReviewIsNotHeldUp() throws Exception {
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<String> first = holdUntil(reviewId, release);
        try {
            assertEquals("other", CompletableFuture.supplyAsync(
                    () -> lock.holding(reviewId + "-TEST-other", () -> "other"), threads)
                    .get(WAIT_SECONDS, TimeUnit.SECONDS));
            assertFalse(first.isDone(), "the first holder still holds its own review's lock");
        } finally {
            release.countDown();
        }
        first.get(WAIT_SECONDS, TimeUnit.SECONDS);
    }

    /** A holder that throws releases the lock with its transaction, so the next one is not stuck behind a failure. */
    @Test
    void aFailedHolderReleasesTheLock() throws Exception {
        assertThrows(IllegalStateException.class, () -> lock.holding(reviewId, () -> {
            throw new IllegalStateException("TEST-failure inside the lock");
        }));

        assertTrue(CompletableFuture.supplyAsync(() -> lock.holding(reviewId, () -> true), threads)
                .get(WAIT_SECONDS, TimeUnit.SECONDS));
    }

    /** Holds the review's lock on a pool thread until {@code release}, and returns once it is held. */
    private CompletableFuture<String> holdUntil(String review, CountDownLatch release) throws InterruptedException {
        CountDownLatch held = new CountDownLatch(1);
        CompletableFuture<String> holder = CompletableFuture.supplyAsync(() -> lock.holding(review, () -> {
            held.countDown();
            awaitQuietly(release);
            return "first";
        }), threads);
        assertTrue(held.await(WAIT_SECONDS, TimeUnit.SECONDS), "the first holder never got the lock");
        return holder;
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            assertTrue(latch.await(WAIT_SECONDS, TimeUnit.SECONDS), "the test never released the lock");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while holding the round lock", e);
        }
    }
}
