package dev.codespire.orchestrator.factory;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.*;

/** Synchronous and asynchronous browser faults, with fail-fast fakes for every unused operation. */
class RunsBroadcasterTest {

    @Test
    void withoutSubscribersItDoesNotReadOrSerialize() {
        RunsBroadcaster subject = subject(List.of());
        // Missing collaborators are deliberate: touching either must fail rather than be swallowed.
        subject.runs = new FactoryRunProjection() {
            @Override
            public Optional<RunListEntry> listOne(String runId) {
                throw new AssertionError("the empty feed must not query the database");
            }

            @Override
            protected void push(String runId) {
                throw new AssertionError("a read fake must not broadcast");
            }
        };
        subject.mapper = null;
        assertDoesNotThrow(() -> subject.push("TEST-run"));
    }

    @Test
    void failedSendsDoNotPreventOtherSubscribersReceivingTheRow() throws Exception {
        List<String> received = new ArrayList<>();
        var syncFailure = connection("runs", payload -> { throw new IllegalStateException("TEST-send-failed"); });
        var asyncFailure = connection("runs", payload -> Uni.createFrom().failure(new IllegalStateException("TEST-async-failed")));
        var otherEndpoint = connection("run-transcript", payload -> { throw new AssertionError("wrong endpoint"); });
        var healthy = connection("runs", payload -> {
            received.add(payload);
            return Uni.createFrom().voidItem();
        });
        RunsBroadcaster subject = subject(List.of(syncFailure, asyncFailure, otherEndpoint, healthy));

        assertDoesNotThrow(() -> subject.push("TEST-run"));

        assertEquals(1, received.size());
        assertEquals("TEST-run", subject.mapper.readTree(received.getFirst()).get("runId").asText());
    }

    @Test
    void anUnreadableRowIsABroadcastFaultNotAProjectionFault() {
        RunsBroadcaster subject = subject(List.of(connection("runs", payload -> {
            throw new AssertionError("an unreadable row cannot be sent");
        })));
        subject.runs = new FactoryRunProjection() {
            @Override
            public Optional<RunListEntry> listOne(String runId) {
                throw new IllegalStateException("TEST-read-failed");
            }

            @Override
            protected void push(String runId) {
                throw new AssertionError("a read fake must not broadcast");
            }
        };
        assertDoesNotThrow(() -> subject.push("TEST-run"));
    }

    @Test
    void anUnserialisableRowWarnsWithoutFailingTheProjection() {
        RunsBroadcaster subject = subject(List.of(connection("runs", payload -> {
            throw new AssertionError("an unserialisable row cannot be sent");
        })));
        subject.mapper = new ObjectMapper() {
            @Override
            public String writeValueAsString(Object value) throws JsonProcessingException {
                throw new JsonProcessingException("TEST-serialization-failure") { };
            }
        };
        List<LogRecord> warnings = new ArrayList<>();
        Handler capture = new Handler() {
            @Override public void publish(LogRecord record) {
                if (record.getLevel().intValue() >= Level.WARNING.intValue()) warnings.add(record);
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        Logger logger = Logger.getLogger(RunsBroadcaster.class.getName());
        logger.addHandler(capture);
        try {
            assertDoesNotThrow(() -> subject.push("TEST-unserialisable-run"));
            assertEquals(1, warnings.size(), "a permanent serialization failure must be visible in production");
            assertTrue(warnings.getFirst().getMessage().contains("TEST-unserialisable-run"));
            assertInstanceOf(JsonProcessingException.class, warnings.getFirst().getThrown());
        } finally {
            logger.removeHandler(capture);
        }
    }

    private static RunsBroadcaster subject(List<WebSocketConnection> subscribers) {
        RunsBroadcaster subject = new RunsBroadcaster();
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
        subject.runs = new FactoryRunProjection() {
            @Override
            public Optional<RunListEntry> listOne(String runId) {
                return Optional.of(new RunListEntry(runId, "queued", "BUILD", "TEST-harness", "TEST-model",
                        "spire/test", null, null, null, null, null, null, RunCost.unknown(), null, null, null));
            }

            @Override
            protected void push(String runId) {
                throw new AssertionError("a read fake must not broadcast");
            }
        };
        return subject;
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
