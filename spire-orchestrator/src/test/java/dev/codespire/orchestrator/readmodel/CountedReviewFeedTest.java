package dev.codespire.orchestrator.readmodel;

import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The stand-in socket keeps the transport's lazy contract (qa/M-2, review of PR #457): Quarkus writes a frame only when
 * the {@code Uni} that {@code sendText} returns is subscribed, so the counter counts subscriptions, not calls.
 */
class CountedReviewFeedTest {

    @Test
    void aSendThatIsNeverSubscribedCountsNothing() {
        List<String> frames = new ArrayList<>();

        CountedReviewFeed.socket(frames).sendText("TEST-frame");

        assertEquals(List.of(), frames);
    }

    @Test
    void aSendSubscribedTwiceCountsTwice() {
        List<String> frames = new ArrayList<>();
        WebSocketConnection socket = CountedReviewFeed.socket(frames);
        Uni<Void> send = socket.sendText("TEST-frame");

        send.await().indefinitely();
        send.await().indefinitely();

        assertEquals(List.of("TEST-frame", "TEST-frame"), frames);
    }
}
