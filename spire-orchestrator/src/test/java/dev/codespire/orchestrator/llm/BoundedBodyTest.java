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
 * The byte bound of a key check's error body (review of PR #393): the reader finishes the moment it
 * has its limit, without waiting for the body to end, and keeps nothing past it. A reader that took
 * everything and truncated afterwards would not be done before the body ended, and the first two cases
 * would fail. It never cancels: the caller closes its client, which ends the body.
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
    void finishesAtTheLimitWithoutWaitingForTheEnd() {
        BoundedBody body = new BoundedBody(10);
        Recorded subscription = new Recorded();
        body.onSubscribe(subscription);

        body.onNext(List.of(bytes(6, 'a'), bytes(6, 'b')));

        assertTrue(body.getBody().toCompletableFuture().isDone(), "done before the body ended");
        assertEquals(0, subscription.cancels, "the client is closed by the caller, not cancelled here");
        assertArrayEquals("aaaaaabbbb".getBytes(), body.getBody().toCompletableFuture().join());
    }

    @Test
    void takesNothingMoreOnceItHasItsLimit() {
        BoundedBody body = new BoundedBody(4);
        Recorded subscription = new Recorded();
        body.onSubscribe(subscription);

        body.onNext(List.of(bytes(4, 'a')));
        body.onNext(List.of(bytes(100, 'b')));

        assertArrayEquals("aaaa".getBytes(), body.getBody().toCompletableFuture().join());
    }

    /** A body nobody needs finishes before a byte is asked for. */
    @Test
    void aZeroLimitFinishesAtOnceAndAsksForNothing() {
        BoundedBody body = new BoundedBody(0);
        Recorded subscription = new Recorded();
        body.onSubscribe(subscription);

        assertTrue(body.getBody().toCompletableFuture().isDone());
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
