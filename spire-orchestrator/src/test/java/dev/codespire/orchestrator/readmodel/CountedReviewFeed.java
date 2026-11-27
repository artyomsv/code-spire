package dev.codespire.orchestrator.readmodel;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.orchestrator.attention.AttentionBroadcaster;
import dev.codespire.orchestrator.llm.catalog.LlmModelCatalog;
import io.quarkus.websockets.next.HandshakeRequest;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;

/**
 * A real {@link ReviewProjection} and a real {@link AnsweringMarks} over it, both on the test's Postgres, with one
 * dashboard connected to {@code /api/ws/reviews} whose frames land in a list (qa/M-2, review of PR #457). Every write,
 * {@code touch} and {@code broadcast} included, runs as in production; only the socket is a stand-in, so a test counts
 * what an open dashboard receives, one frame per subscribed send. A fresh instance rather than the injected projection: that one is a client proxy,
 * and a field set on it never reaches the instance its calls delegate to.
 */
@Singleton
public class CountedReviewFeed {

    private static final String REVIEWS_SOCKET = "/api/ws/reviews";

    @Inject
    DataSource dataSource;

    @Inject
    ObjectMapper mapper;

    @Inject
    EncryptionService encryption;

    @Inject
    AttentionBroadcaster attention;

    @Inject
    LlmModelCatalog catalog;

    /** The real projection; each review frame it pushes is added to {@code frames}. */
    public ReviewProjection projection(List<String> frames) {
        ReviewProjection projection = new ReviewProjection();
        projection.dataSource = dataSource;
        projection.mapper = mapper;
        projection.encryption = encryption;
        projection.attention = attention;
        projection.catalog = catalog;
        projection.connections = connections(socket(frames));
        return projection;
    }

    /** The real marks, broadcasting through {@code projection}. */
    public AnsweringMarks marks(ReviewProjection projection) {
        AnsweringMarks marks = new AnsweringMarks();
        marks.dataSource = dataSource;
        marks.projection = projection;
        return marks;
    }

    private static OpenConnections connections(WebSocketConnection socket) {
        return new OpenConnections() {
            @Override
            public Stream<WebSocketConnection> stream() {
                return Stream.of(socket);
            }

            @Override
            public Iterator<WebSocketConnection> iterator() {
                return stream().iterator();
            }
        };
    }

    /** Only what {@code ReviewProjection.push} calls is answered; anything else fails the test. */
    static WebSocketConnection socket(List<String> frames) {
        HandshakeRequest handshake = proxy(HandshakeRequest.class, (self, method, args) -> {
            if ("path".equals(method.getName())) {
                return REVIEWS_SOCKET;
            }
            throw new UnsupportedOperationException(method.getName());
        });
        return proxy(WebSocketConnection.class, (self, method, args) -> switch (method.getName()) {
            case "handshakeRequest" -> handshake;
            case "sendText" -> sent(frames, String.valueOf(args[0]));
            default -> throw new UnsupportedOperationException(method.getName());
        });
    }

    /**
     * Records the frame inside the subscription, as the transport writes it: Quarkus's {@code sendText} only builds a
     * lazy {@code Uni}, so a send never subscribed counts nothing and one subscribed twice counts twice.
     */
    private static Uni<Void> sent(List<String> frames, String frame) {
        return Uni.createFrom().voidItem().invoke(() -> frames.add(frame));
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, handler));
    }
}
