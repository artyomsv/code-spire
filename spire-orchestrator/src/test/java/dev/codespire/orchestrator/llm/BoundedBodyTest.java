package dev.codespire.orchestrator.llm;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The byte bound of a key check's error body, measured at the subscription (review of PR #393): the
 * reader cancels the moment it has its limit, without waiting for the body to end. A reader that took
 * everything and truncated afterwards would never cancel, and every case below would fail.
 */
class BoundedBodyTest {

    /** A subscription that records what the reader asked of it. */
    private static final class Recorded implements Flow.Subscription {
        long requested;
        int cancels;

        @Override
        public void request(long n) {
            requested += n;
        }

        @Override
        public void cancel() {
            cancels++;
        }
    }

    private static ByteBuffer bytes(int count, char fill) {
        return ByteBuffer.wrap(String.valueOf(fill).repeat(count).getBytes());
    }

    @Test
    void stopsAndCancelsAtTheLimitWithoutWaitingForTheEnd() {
        BoundedBody body = new BoundedBody(10);
        Recorded subscription = new Recorded();
        body.onSubscribe(subscription);

        body.onNext(List.of(bytes(6, 'a'), bytes(6, 'b')));

        assertEquals(1, subscription.cancels, "cancelled once, at the limit");
        assertTrue(body.getBody().toCompletableFuture().isDone(), "done before the body ended");
        assertArrayEquals("aaaaaabbbb".getBytes(), body.getBody().toCompletableFuture().join());
    }

    @Test
    void takesNothingMoreOnceItHasItsLimit() {
        BoundedBody body = new BoundedBody(4);
        Recorded subscription = new Recorded();
        body.onSubscribe(subscription);

        body.onNext(List.of(bytes(4, 'a')));
        body.onNext(List.of(bytes(100, 'b')));

        assertEquals(1, subscription.cancels);
        assertArrayEquals("aaaa".getBytes(), body.getBody().toCompletableFuture().join());
    }

    /** A body nobody needs is cancelled before a byte is asked for. */
    @Test
    void aZeroLimitCancelsAtOnceAndAsksForNothing() {
        BoundedBody body = new BoundedBody(0);
        Recorded subscription = new Recorded();
        body.onSubscribe(subscription);

        assertEquals(1, subscription.cancels);
        assertEquals(0, subscription.requested);
        assertEquals(0, body.getBody().toCompletableFuture().join().length);
    }

    @Test
    void aShortBodyCompletesWithWhatArrived() {
        BoundedBody body = new BoundedBody(16);
        Recorded subscription = new Recorded();
        body.onSubscribe(subscription);

        body.onNext(List.of(bytes(3, 'a')));
        assertFalse(body.getBody().toCompletableFuture().isDone(), "still reading a body under its limit");
        body.onComplete();

        assertEquals(0, subscription.cancels);
        assertArrayEquals("aaa".getBytes(), body.getBody().toCompletableFuture().join());
    }

    /** A broken body is a hint lost, not a failure: the check still answers by status. */
    @Test
    void aBrokenBodyCompletesWithWhatArrived() {
        BoundedBody body = new BoundedBody(16);
        body.onSubscribe(new Recorded());

        body.onNext(List.of(bytes(2, 'a')));
        body.onError(new java.io.IOException("TEST-broken"));

        assertArrayEquals("aa".getBytes(), body.getBody().toCompletableFuture().join());
    }
}
