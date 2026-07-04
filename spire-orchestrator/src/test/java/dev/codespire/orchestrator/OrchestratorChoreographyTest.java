package dev.codespire.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.PrAction;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.DiffRefs;
import dev.codespire.contract.scm.RepoRef;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

import static io.restassured.RestAssured.get;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Split test for the orchestrator deployable: the saga choreography over the
 * real broker (Dev Services Kafka — the test talks to the APP's own
 * kafka.bootstrap.servers, so there is exactly one broker). The test plays
 * gateway (cs.integration) and worker (cs.results) and asserts the
 * orchestrator's commands (cs.commands), the aggregate outcome, and the
 * relocated ADR-013 stale-run guard: results of a superseded commit trigger
 * NOTHING.
 */
@QuarkusTest
class OrchestratorChoreographyTest {

    private static final RepoRef REPO = new RepoRef("sandbox", "demo-repo");
    private static final String REVIEW_ID = "review::sandbox/demo-repo#77";
    private static final String COMMIT_A = "aaa111aaa111";
    private static final String COMMIT_B = "bbb222bbb222";

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    @Inject
    ObjectMapper mapper;

    private KafkaProducer<String, String> producer;

    @AfterEach
    void closeProducer() {
        if (producer != null) {
            producer.close();
        }
    }

    @Test
    void choreographyCompletesAndStaleResultsAreDropped() throws Exception {
        // PR opened with commit A -> orchestrator starts the run
        produce("cs.integration", prEvent(COMMIT_A, PrAction.OPENED));
        expectCommands(1, "FetchDiff");

        // worker results drive the next commands
        produce("cs.results", new IntegrationEvent.DiffFetched(REVIEW_ID, 77, COMMIT_A, 1, List.of("java"), 100, false));
        expectCommands(2, "GatherContext");

        produce("cs.results", new IntegrationEvent.ContextAssembled(REVIEW_ID, 77, COMMIT_A, null, Set.of("RULES"), Set.of()));
        expectCommands(3, "GenerateReview");

        // commit B supersedes A mid-run
        produce("cs.integration", prEvent(COMMIT_B, PrAction.UPDATED));
        expectCommands(4, "FetchDiff");

        // a STALE result for A arrives after the supersede -> dropped, no PostComments
        produce("cs.results", new IntegrationEvent.ReviewGenerated(REVIEW_ID, 77, COMMIT_A,
                new ReviewResult(List.of(), "stale summary", new ModelUsage("m", 0, 0, 0))));

        // B's flow continues normally
        produce("cs.results", new IntegrationEvent.DiffFetched(REVIEW_ID, 77, COMMIT_B, 1, List.of("java"), 100, false));
        List<String> commands = expectCommands(5, "GatherContext");
        assertTrue(commands.stream().noneMatch(c ->
                        c.contains("\"type\":\"PostComments\"") && c.contains(COMMIT_A)),
                "stale ReviewGenerated for a superseded commit must not produce PostComments");

        // finish B's run
        produce("cs.results", new IntegrationEvent.ContextAssembled(REVIEW_ID, 77, COMMIT_B, null, Set.of("RULES"), Set.of()));
        produce("cs.results", new IntegrationEvent.ReviewGenerated(REVIEW_ID, 77, COMMIT_B,
                new ReviewResult(List.of(), "summary B", new ModelUsage("m", 0, 0, 0))));
        // B: ContextAssembled -> GenerateReview (#6), ReviewGenerated -> PostComments (#7)
        List<String> all = expectCommands(7, "PostComments");
        assertTrue(all.stream().anyMatch(c -> c.contains("\"type\":\"PostComments\"") && c.contains(COMMIT_B)));

        produce("cs.results", new IntegrationEvent.CommentsPosted(REVIEW_ID, 77, COMMIT_B, "c-1", List.of()));
        awaitTimelineContains("\"ReviewCompleted\"");
        String timeline = get("/api/timeline").asString();
        assertTrue(timeline.contains("dropped:ReviewGenerated"), "stale drop must be visible on the timeline");

        // --- QA gap #2: PullRequestClosed -> CancelReview over the bus (PR #78) ---
        String reviewId78 = "review::sandbox/demo-repo#78";
        produce78(prEvent78(PrAction.OPENED));
        expectCommands(8, "FetchDiff"); // #78's run starts

        produce78(new IntegrationEvent.PullRequestClosed(REPO, 78, IntegrationEvent.CloseReason.MERGED));
        awaitTimelineContains("\"ReviewCancelled\"");

        // a late worker result for the cancelled run triggers NOTHING
        produce78(new IntegrationEvent.DiffFetched(reviewId78, 78, "ccc333ccc333", 1, List.of("java"), 10, false));
        awaitTimelineContains("dropped:DiffFetched");
        List<String> afterCancel = expectCommands(8, "FetchDiff"); // still exactly 8 commands
        assertTrue(afterCancel.stream()
                        .filter(c -> c.contains("#78")).count() == 1,
                "the cancelled run must produce no commands beyond its initial FetchDiff");
    }

    private IntegrationEvent prEvent78(PrAction action) {
        return new IntegrationEvent.PullRequestEventReceived(
                REPO, 78, action, "TEST: cancel flow", "TEST description",
                "feature/TEST-cancel", "main", DiffRefs.headOnly("ccc333ccc333"),
                Author.of("TEST-account-id", "test-author", "TEST Author"),
                "https://example.invalid/pr/78");
    }

    private void produce78(IntegrationEvent event) throws Exception {
        String topic = event instanceof IntegrationEvent.DiffFetched ? "cs.results" : "cs.integration";
        String json = mapper.writerFor(IntegrationEvent.class).writeValueAsString(event);
        producer.send(new ProducerRecord<>(topic, "review::sandbox/demo-repo#78", json)).get();
    }

    private IntegrationEvent prEvent(String commit, PrAction action) {
        return new IntegrationEvent.PullRequestEventReceived(
                REPO, 77, action, "TEST: choreography", "TEST description",
                "feature/TEST-x", "main", DiffRefs.headOnly(commit),
                Author.of("TEST-account-id", "test-author", "TEST Author"),
                "https://example.invalid/pr/77");
    }

    private void produce(String topic, IntegrationEvent event) throws Exception {
        if (producer == null) {
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            producer = new KafkaProducer<>(props);
        }
        // writerFor: root-level polymorphism (the type discriminator)
        String json = mapper.writerFor(IntegrationEvent.class).writeValueAsString(event);
        producer.send(new ProducerRecord<>(topic, REVIEW_ID, json)).get();
    }

    /** Reads cs.commands from the beginning until {@code total} records; asserts the last one's type. */
    private List<String> expectCommands(int total, String expectedLastType) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        List<String> values = new ArrayList<>();
        long deadline = System.currentTimeMillis() + 30_000;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("cs.commands"));
            while (values.size() < total && System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    values.add(record.value());
                }
            }
        }
        assertTrue(values.size() >= total,
                "expected " + total + " commands within 30s, got " + values.size() + ": " + values);
        assertTrue(values.get(total - 1).contains("\"type\":\"" + expectedLastType + "\""),
                "expected command #" + total + " to be " + expectedLastType + " but got: " + values.get(total - 1));
        return values;
    }

    private void awaitTimelineContains(String marker) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            if (get("/api/timeline").asString().contains(marker)) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Timeline never showed " + marker);
    }
}
