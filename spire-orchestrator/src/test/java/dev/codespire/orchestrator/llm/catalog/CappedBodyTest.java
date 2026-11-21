package dev.codespire.orchestrator.llm.catalog;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The subscriber on its own, with a subscription that records what it was told: past the cap it cancels and its body
 * is exactly the cap, whatever more was offered. No server, no sleeps.
 */
class CappedBodyTest {

    private static final int CAP = 10;

    /** Records a cancel; the bytes are pushed by the test. */
    private static final class RecordingSubscription implements Flow.Subscription {
        boolean cancelled;

        @Override
        public void request(long n) {
            // The test pushes the chunks itself.
        }

        @Override
        public void cancel() {
            cancelled = true;
        }
    }

    @Test
    void itCancelsAtTheCapAndKeepsExactlyTheCap() throws Exception {
        CappedBody body = new CappedBody(CAP);
        RecordingSubscription subscription = new RecordingSubscription();
        body.onSubscribe(subscription);

        body.onNext(List.of(chunk(4, (byte) 1)));
        body.onNext(List.of(chunk(4, (byte) 2)));
        assertFalse(subscription.cancelled, "under the cap the read goes on");
        body.onNext(List.of(chunk(4, (byte) 3)));

        assertTrue(subscription.cancelled, "past the cap the subscription is cancelled");
        byte[] read = body.getBody().toCompletableFuture().get(1, TimeUnit.SECONDS);
        assertEquals(CAP, read.length, "exactly the cap, not the twelve bytes offered");
        assertArrayEquals(new byte[] {1, 1, 1, 1, 2, 2, 2, 2, 3, 3}, read);
    }

    @Test
    void aBodyUnderTheCapCompletesWhole() throws Exception {
        CappedBody body = new CappedBody(CAP);
        RecordingSubscription subscription = new RecordingSubscription();
        body.onSubscribe(subscription);

        body.onNext(List.of(chunk(3, (byte) 1)));
        body.onComplete();

        assertFalse(subscription.cancelled);
        assertEquals(3, body.getBody().toCompletableFuture().get(1, TimeUnit.SECONDS).length);
    }

    private static ByteBuffer chunk(int length, byte value) {
        byte[] bytes = new byte[length];
        java.util.Arrays.fill(bytes, value);
        return ByteBuffer.wrap(bytes);
    }
}
