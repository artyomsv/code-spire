package dev.codespire.orchestrator.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codespire.contract.event.RepositoryRegistration;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kafka.InjectKafkaCompanion;
import io.quarkus.test.kafka.KafkaCompanionResource;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import jakarta.inject.Inject;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@QuarkusTestResource(KafkaCompanionResource.class)
class RepositorySnapshotConsumerTest extends RepositoryFixture {
    @InjectKafkaCompanion KafkaCompanion companion;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name = "kafka.bootstrap.servers") String bootstrapServers;

    @Test void blankLegacyOriginIsPendingAndAcknowledged() throws Exception {
        account("REVIEWER"); snapshotAccounts();
        UUID registration = UUID.randomUUID();
        RepositoryRegistration snapshot = new RepositoryRegistration(registration, 7, "gitlab", null, "repo",
                workspace + "/TEST-repo", true, false);
        ObjectNode wire = mapper.valueToTree(snapshot);
        wire.put("forgeOrigin", ""); // Legacy wire data bypasses the strict producer-side record constructor.
        try (KafkaProducer<String, String> producer = new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers,
                ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class));
             AdminClient admin = AdminClient.create(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers))) {
            RecordMetadata sent = producer.send(new ProducerRecord<>("cs.registry-integration", registration.toString(),
                    mapper.writeValueAsString(wire))).get(15, TimeUnit.SECONDS);
            TopicPartition partition = new TopicPartition(sent.topic(), sent.partition());
            await().atMost(Duration.ofSeconds(20)).untilAsserted(() -> {
                OffsetAndMetadata processed = admin.listConsumerGroupOffsets("spire-orchestrator-registry")
                        .partitionsToOffsetAndMetadata().get(5, TimeUnit.SECONDS).get(partition);
                assertNotNull(processed, "the real consumer must commit its processed offset");
                assertTrue(processed.offset() > sent.offset(), "the blank snapshot must be acknowledged");
            });
            // An offset alone could mean DLQ delivery. Require the committed repair mapping as well.
            try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                    "SELECT revision,problem,forge_origin,repository_id FROM repository_registration_bridge WHERE registration_id=?")) {
                statement.setObject(1, registration);
                try (ResultSet rows = statement.executeQuery()) {
                    assertTrue(rows.next(), "processed snapshot must have a durable pending mapping, not only a DLQ record");
                    assertEquals(7, rows.getLong("revision"));
                    assertEquals("registration_origin_unknown", rows.getString("problem"));
                    assertNull(rows.getString("forge_origin"));
                    assertNull(rows.getObject("repository_id"));
                }
            }
        }
    }

    @Test void brokerDeliveryReconcilesTheActualConsumerAndRegistry() throws Exception {
        UUID reviewer = account("REVIEWER"); snapshotAccounts();
        UUID registration = UUID.randomUUID();
        var snapshot = new RepositoryRegistration(registration, 1, "gitlab", origin, "repo", workspace + "/TEST-repo", true, false);
        companion.produceStrings().fromRecords(new ProducerRecord<>("cs.registry-integration", registration.toString(), mapper.writeValueAsString(snapshot)))
                .awaitCompletion(Duration.ofSeconds(15));
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {
            var match = repositories.list().stream().filter(row -> row.workspace().equals(workspace)).findFirst();
            assertTrue(match.isPresent(), "the actual consumer must create the registry row");
            var repo = match.orElseThrow();
            assertEquals(reviewer, repo.reviewer().id()); assertNull(repo.factory());
        });
    }
}
