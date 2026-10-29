package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** The frames the work-item screens receive, and one browser's fault kept from the others. */
class WorkItemsBroadcasterTest {

    @Test
    void withoutSubscribersNothingIsSerialised() {
        WorkItemsBroadcaster subject = subject(List.of());
        // A missing mapper is deliberate: touching it must fail rather than be swallowed.
        subject.mapper = null;
        assertDoesNotThrow(() -> subject.changed(Set.of("TEST-item")));
        assertDoesNotThrow(subject::refreshAll);
    }

    @Test
    void idsAreSortedAndDeduplicated() {
        List<String> received = new ArrayList<>();
        WorkItemsBroadcaster subject = subject(List.of(receiver(received::add)));

        subject.changed(Set.of("TEST-b", "TEST-c", "TEST-a"));

        assertEquals(List.of("{\"changed\":[\"TEST-a\",\"TEST-b\",\"TEST-c\"]}"), received);
    }

    @Test
    void aStarInTheBatchSendsOneStarFrame() {
        List<String> received = new ArrayList<>();
        WorkItemsBroadcaster subject = subject(List.of(receiver(received::add)));

        subject.changed(Set.of("TEST-a", "*"));
        subject.refreshAll();
        subject.changed(Set.of());

        assertEquals(List.of("{\"changed\":\"*\"}", "{\"changed\":\"*\"}"), received, "an empty batch sends nothing");
    }

    @Test
    void aFailedSendDoesNotStopTheOtherSubscribers() {
        List<String> received = new ArrayList<>();
        AtomicBoolean asyncFailureSubscribed = new AtomicBoolean();
        var syncFailure = connection(WorkItemsBroadcaster.ENDPOINT_ID, payload -> { throw new IllegalStateException("TEST-send-failed"); });
        var asyncFailure = connection(WorkItemsBroadcaster.ENDPOINT_ID, payload -> Uni.createFrom().failure(() -> {
            asyncFailureSubscribed.set(true);
            return new IllegalStateException("TEST-async-failed");
        }));
        // A browser that never acknowledges: delivery to the next subscriber must not wait for it.
        var stalled = connection(WorkItemsBroadcaster.ENDPOINT_ID, payload -> Uni.createFrom().nothing());
        var otherEndpoint = connection("runs", payload -> { throw new AssertionError("wrong endpoint"); });
        var healthy = receiver(received::add);
        WorkItemsBroadcaster subject = subject(List.of(syncFailure, asyncFailure, stalled, otherEndpoint, healthy));

        assertTimeoutPreemptively(Duration.ofSeconds(1), () -> subject.changed(Set.of("TEST-item")));

        assertTrue(asyncFailureSubscribed.get(), "the failing send was never subscribed, so its failure was never observed");
        assertEquals(List.of("{\"changed\":[\"TEST-item\"]}"), received);
    }

    private static WorkItemsBroadcaster subject(List<WebSocketConnection> subscribers) {
        WorkItemsBroadcaster subject = new WorkItemsBroadcaster();
        subject.connections = new OpenConnections() {
            @Override
            public java.util.stream.Stream<WebSocketConnection> stream() {
                return subscribers.stream();
            }

            @Override
            public java.util.Iterator<WebSocketConnection> iterator() {
                return subscribers.iterator();
            }
        };
        subject.mapper = new ObjectMapper();
        return subject;
    }

    /** Records a frame only when its send is subscribed, as the real connection sends only then. */
    private static WebSocketConnection receiver(java.util.function.Consumer<String> receive) {
        return connection(WorkItemsBroadcaster.ENDPOINT_ID, payload -> Uni.createFrom().item(() -> {
            receive.accept(payload);
            return null;
        }));
    }

    private static WebSocketConnection connection(String endpoint, Function<String, Uni<Void>> send) {
        return (WebSocketConnection) Proxy.newProxyInstance(WebSocketConnection.class.getClassLoader(),
                new Class<?>[]{WebSocketConnection.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "endpointId" -> endpoint;
                    case "sendText" -> send.apply((String) args[0]);
                    default -> throw new AssertionError("unexpected connection method: " + method.getName());
                });
    }
}
