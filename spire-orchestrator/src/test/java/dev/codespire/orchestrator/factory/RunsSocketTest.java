package dev.codespire.orchestrator.factory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.RunResult;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import javax.sql.DataSource;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/** Real HTTP upgrade, real socket frames and committed database rows, without replacing the feed. */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = "spire-admin")
class RunsSocketTest {

    @Inject
    FactoryRunProjection runs;

    @Inject
    ObjectMapper mapper;

    @Inject
    DataSource dataSource;

    @Inject
    RunResultSaga saga;

    @TestHTTPResource("/api/ws/runs")
    URI endpoint;

    @AfterEach
    void removeOwnedRows() throws Exception {
        sql("DELETE FROM llm_charge WHERE subject_id LIKE 'run::github:TEST-live/%'");
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                "DELETE FROM factory_run WHERE workspace = 'TEST-live'")) {
            statement.executeUpdate();
        }
        sql("DELETE FROM llm_model WHERE name LIKE 'TEST-live-cost-%'");
    }

    @Test
    void openingReturnsTheSameUnfilteredNewestTwoHundredRowsAsTheList() throws Exception {
        for (int i = 0; i < 201; i++) queue();
        String failed = queue();
        runs.dispatchFailed(failed, "TEST-dispatch-failed");
        try (Feed feed = open()) {
            JsonNode snapshot = feed.next();
            assertTrue(snapshot.isArray());
            assertEquals(mapper.valueToTree(runs.list(new FactoryRunProjection.RunFilter(null, null, null, 200))), snapshot);
            assertEquals(200, snapshot.size());
            assertTrue(snapshot.findValuesAsText("status").contains("failed"));
            assertTrue(snapshot.findValuesAsText("status").contains("queued"));
        }
    }

    @Test
    void aRunAppearsThenStartsFinishesAndGainsItsPullRequestWithoutReopeningTheSocket() throws Exception {
        try (Feed feed = open()) {
            feed.next();
            String runId = queue();
            assertRow(feed.next(), runId, "queued");
            runs.apply(new RunResult.RunStarted(runId, "TEST-unit"));
            JsonNode running = feed.next();
            assertRow(running, runId, "running");
            assertFalse(running.get("agentStartedAt").isNull());

            runs.apply(new RunResult.RunFinished(runId, "refs/heads/spire/test", List.of(), List.of(), null, false));
            JsonNode finished = feed.next();
            assertRow(finished, runId, "succeeded");
            assertTrue(finished.get("prUrl").isNull());
            runs.pullRequestOpened(runId, 42, "https://github.invalid/acme/app/pull/42");
            JsonNode proposed = feed.next();
            assertRow(proposed, runId, "succeeded");
            assertEquals("https://github.invalid/acme/app/pull/42", proposed.get("prUrl").asText());
            assertNull(feed.frames.poll(200, TimeUnit.MILLISECONDS), "one frame per write, including exactly two for finish then proposal");
        }
    }

    @Test
    void dispatchResolutionFailureAndAttentionWritesAlsoPush() throws Exception {
        String runId = queue();
        try (Feed feed = open()) {
            feed.next();
            runs.dispatchUncertain(runId, "TEST-uncertain");
            assertRow(feed.next(), runId, "dispatch_uncertain");
            assertTrue(runs.resolveAsNeverRan(runId));
            assertRow(feed.next(), runId, "failed");
            requeue(runId);
            assertRow(feed.next(), runId, "queued");
            runs.dispatchFailed(runId, "TEST-failed");
            assertRow(feed.next(), runId, "failed");
            requeue(runId);
            assertRow(feed.next(), runId, "queued");
            runs.dispatchUncertain(runId, "TEST-uncertain");
            assertRow(feed.next(), runId, "dispatch_uncertain");
            assertTrue(runs.resolveAsStarted(runId));
            assertRow(feed.next(), runId, "failed");
            runs.apply(new RunResult.RunStarted(runId, "TEST-unit"));
            assertRow(feed.next(), runId, "running");
            runs.apply(new RunResult.RunFailed(runId, "SANDBOX_UNREACHABLE", "TEST-crash", true, null));
            assertRow(feed.next(), runId, "failed");
            runs.pullRequestFailed(runId, "TEST-proposal-error");
            assertEquals("TEST-proposal-error", feed.next().get("prError").asText());
            assertTrue(runs.acknowledgeAttention(runId));
            assertRow(feed.next(), runId, "failed");
        }
    }

    @Test
    void listOneFindsOnlyTheRequestedRowAndSharesEveryListField() {
        String mine = queue();
        queue(); // A newer row would be returned if listOne forgot the run-id predicate.
        var expected = runs.list(new FactoryRunProjection.RunFilter(null, null, null, 200)).stream()
                .filter(row -> row.runId().equals(mine)).findFirst().orElseThrow();
        assertEquals(expected, runs.listOne(mine).orElseThrow());
        assertTrue(runs.listOne("run::github:TEST-live/app:missing:1").isEmpty());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancelled", "delivered_nothing", "delivered_unfinished", "push_gate_refused"})
    void everyOtherTerminalStatusIsPushed(String status) throws Exception {
        String runId = queue();
        RunResult result = switch (status) {
            case "cancelled" -> new RunResult.RunFailed(runId, "CANCELLED", "TEST-cancel", false, null);
            case "delivered_nothing" -> new RunResult.RunFinished(runId, null, List.of(), List.of(), null, false);
            case "delivered_unfinished" -> new RunResult.RunFinished(runId, "refs/heads/spire/test", List.of(), List.of(), null, true);
            default -> new RunResult.RunFinished(runId, null, List.of(),
                    List.of(new RunResult.BlockedChange("secrets.txt", "TEST-blocked")), null, false);
        };
        try (Feed feed = open()) {
            feed.next();
            runs.apply(result);
            assertRow(feed.next(), runId, status);
            assertNull(feed.frames.poll(200, TimeUnit.MILLISECONDS), "one result must produce one push");
        }
    }

    @Test
    void aBrokenBroadcasterCannotFailACommittedProjectionWrite() {
        FactoryRunProjection subject = new FactoryRunProjection();
        subject.dataSource = dataSource;
        subject.broadcaster = new RunsBroadcaster() {
            @Override
            public void push(String runId) {
                throw new IllegalStateException("TEST-broadcast-failed");
            }
        };
        String runId = queue();
        assertDoesNotThrow(() -> subject.apply(new RunResult.RunStarted(runId, "TEST-unit")));
        assertEquals("running", runs.find(runId).orElseThrow().status());
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void spendArrivesAfterTheTerminalStatusWithoutNeedingAPullRequestWrite(boolean fix) throws Exception {
        String model = "TEST-live-cost-" + UUID.randomUUID();
        UUID modelId = UUID.randomUUID();
        sql("INSERT INTO llm_model (id, type, name, label, pricing_mode) VALUES (?, 'openai', ?, ?, 'METERED')",
                modelId, model, model);
        sql("INSERT INTO llm_model_rate (model_id, token_type, rate_millicents_per_million) "
                + "VALUES (?, 'INPUT', 1000000), (?, 'OUTPUT', 1000000)", modelId, modelId);
        String runId = "run::github:TEST-live/app:" + UUID.randomUUID() + ":1";
        var row = new FactoryRunProjection.QueuedRun(runId, "codex", model, "main", "TEST-SHA",
                "feature/test", "TEST-bot", null);
        if (fix) row = row.asFixFor("review::TEST-live/app#42", "TEST-finding", "comment-" + UUID.randomUUID());
        assertTrue(runs.queued(row, "TEST-task", null));
        try (Feed feed = open()) {
            feed.next();
            saga.on(new RunResult.RunFinished(runId, fix ? "refs/heads/feature/test" : null,
                    List.of(), List.of(), Map.of("INPUT", 100L, "OUTPUT", 40L), false));

            JsonNode terminal = feed.next();
            assertRow(terminal, runId, fix ? "succeeded" : "delivered_nothing");
            assertTrue(terminal.get("cost").get("millicents").isNull(), "the outcome arrives before charging");
            JsonNode charged = feed.next();
            assertRow(charged, runId, fix ? "succeeded" : "delivered_nothing");
            assertEquals(140, charged.get("cost").get("millicents").asLong());
            assertTrue(charged.get("prUrl").isNull(), "neither outcome needs a new PR");
            assertEquals(new RunSpend(140, 0, Map.of("INPUT", 100L, "OUTPUT", 40L)), runs.find(runId).orElseThrow().spend());
            assertNull(feed.frames.poll(200, TimeUnit.MILLISECONDS));
        }
    }

    private void sql(String query, Object... args) throws Exception {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(query)) {
            for (int i = 0; i < args.length; i++) statement.setObject(i + 1, args[i]);
            statement.executeUpdate();
        }
    }

    private void assertRow(JsonNode row, String runId, String status) {
        assertTrue(row.isObject());
        assertEquals(runId, row.get("runId").asText());
        assertEquals(status, row.get("status").asText());
    }

    private String queue() {
        String runId = "run::github:TEST-live/app:" + UUID.randomUUID() + ":1";
        requeue(runId);
        return runId;
    }

    private void requeue(String runId) {
        assertTrue(runs.queued(new FactoryRunProjection.QueuedRun(runId, "codex", "TEST-MODEL",
                "main", "TEST-SHA", "spire/test", "TEST-bot", null), "TEST-task", null));
    }

    private Feed open() throws Exception {
        Feed feed = new Feed();
        feed.socket = feed.client.newWebSocketBuilder()
                .buildAsync(URI.create(endpoint.toString().replaceFirst("^http", "ws")), feed)
                .get(10, TimeUnit.SECONDS);
        return feed;
    }

    private final class Feed implements WebSocket.Listener, AutoCloseable {
        final HttpClient client = HttpClient.newHttpClient();
        final BlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final StringBuilder fragments = new StringBuilder();
        WebSocket socket;

        @Override
        public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
            fragments.append(data);
            if (last) {
                frames.add(fragments.toString());
                fragments.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        JsonNode next() throws Exception {
            String frame = frames.poll(10, TimeUnit.SECONDS);
            assertNotNull(frame, "the live runs socket delivered no frame");
            return mapper.readTree(frame);
        }

        @Override
        public void close() {
            if (socket != null) socket.abort();
            client.close();
        }
    }
}
