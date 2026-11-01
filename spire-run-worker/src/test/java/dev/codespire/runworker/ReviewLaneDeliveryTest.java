package dev.codespire.runworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.event.RunReviewArtifact;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.scm.RepoRef;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kafka.KafkaCompanionResource;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The review lane through the real broker and the real consumer (review of PR #367, code-quality/M-1).
 *
 * <p>Two halves, because one test cannot hold the real consumer back from its first assignment: the application's
 * group is assigned before a test method runs. So:
 * <ul>
 *   <li>a fresh group, configured with the lane's own offset reset, reads a command sent BEFORE it subscribed. Under
 *       {@code latest} that command was skipped for ever while its row stayed queued and a retry answered
 *       {@code AlreadyQueued};</li>
 *   <li>the real lane executes a command once, and a second delivery of the same record starts nothing, so reading
 *       from the start of the topic costs no second unit.</li>
 * </ul>
 * What this does not prove: the real consumer's own first-assignment race, which needs a group assigned after the send.
 * The first half proves the configuration that closes it on the same broker; the claims prove the replay is safe.
 */
@QuarkusTest
@QuarkusTestResource(KafkaCompanionResource.class)
class ReviewLaneDeliveryTest {

    private static final String TOPIC = "cs.review-runs";

    private static final String GROUP = "spire-run-worker-reviews";

    private static final ObjectMapper JSON = new ObjectMapper();

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    @ConfigProperty(name = "mp.messaging.incoming.review-runs-in.auto.offset.reset")
    String offsetReset;

    private final AtomicInteger launches = new AtomicInteger();

    @BeforeEach
    void countLaunches() {
        QuarkusMock.installMockForType(new RunLauncher() {
            @Override
            public RunResult launchReview(RunCommand.ExecuteReviewRun review, RunObserver observer) {
                launches.incrementAndGet();
                return new RunReviewArtifact(review.runId(), review.review(), "TEST-findings", false, null, 1, List.of());
            }
        }, RunLauncher.class);
    }

    @Test
    void aFreshGroupReadsACommandSentBeforeItSubscribed() throws Exception {
        String key = send(command()).key();

        try (KafkaConsumer<String, String> fresh = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ConsumerConfig.GROUP_ID_CONFIG, "TEST-fresh-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, offsetReset,
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class))) {
            fresh.subscribe(List.of(TOPIC));
            assertTrue(seen(fresh, key), "a command sent before the group was assigned was skipped (offset reset "
                    + offsetReset + "): its row would stay queued for ever");
        }
    }

    @Test
    void theLaneRunsACommandOnceAndASecondDeliveryStartsNothing() throws Exception {
        RunCommand.ExecuteReviewRun command = command();

        send(command);
        assertTrue(eventually(() -> launches.get() == 1), "the lane never ran the command");
        Sent again = send(command);
        awaitProcessed(again.metadata());

        assertEquals(1, launches.get(), "the execute claim drops the second delivery");
    }

    private static RunCommand.ExecuteReviewRun command() {
        String runId = "run::github:TEST-work/app:TEST-lane-" + UUID.randomUUID() + ":1";
        return new RunCommand.ExecuteReviewRun(runId, new RunCommand.ExecuteRun(runId, new RepoRef("TEST-work", "app"),
                "https://forge.example.test/TEST-work/app.git", "TEST-source", "a".repeat(40), "TEST-source",
                "TEST-review the pull request", "codex", "TEST-model", "TEST-image", List.of(), 60, "enc-scm", "enc-harness"),
                new ReviewRunBinding("TEST-review", ReviewRunBinding.Kind.REVIEW, 7, "a".repeat(40), "TEST-slot"));
    }

    private record Sent(String key, RecordMetadata metadata) {
    }

    private Sent send(RunCommand command) throws Exception {
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class))) {
            RecordMetadata metadata = producer.send(new ProducerRecord<>(TOPIC, command.runId(),
                    JSON.writeValueAsString(command))).get(15, TimeUnit.SECONDS);
            return new Sent(command.runId(), metadata);
        }
    }

    private static boolean seen(KafkaConsumer<String, String> consumer, String key) {
        Instant until = Instant.now().plusSeconds(20);
        while (Instant.now().isBefore(until)) {
            for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                if (key.equals(record.key())) {
                    return true;
                }
            }
        }
        return false;
    }

    /** Waits until the lane's group has committed past the record, so the second delivery was really read. */
    private void awaitProcessed(RecordMetadata sent) {
        TopicPartition partition = new TopicPartition(sent.topic(), sent.partition());
        try (AdminClient admin = AdminClient.create(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            assertTrue(eventually(() -> committedPast(admin, partition, sent.offset())),
                    "the lane never committed past the second delivery");
        }
    }

    private static boolean committedPast(AdminClient admin, TopicPartition partition, long offset) {
        try {
            OffsetAndMetadata committed = admin.listConsumerGroupOffsets(GROUP)
                    .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS).get(partition);
            return committed != null && committed.offset() > offset;
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return false; // the group may not exist yet; the poll asks again
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Polls for up to 30 seconds; no sleep-and-hope, and no dependency beyond the JDK. */
    private static boolean eventually(BooleanSupplier condition) {
        Instant until = Instant.now().plusSeconds(30);
        while (Instant.now().isBefore(until)) {
            if (condition.getAsBoolean()) {
                return true;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return condition.getAsBoolean();
    }
}
