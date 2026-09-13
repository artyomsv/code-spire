package dev.codespire.orchestrator.repository;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.RepositoryRegistration;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kafka.InjectKafkaCompanion;
import io.quarkus.test.kafka.KafkaCompanionResource;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import jakarta.inject.Inject;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.UUID;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@QuarkusTestResource(KafkaCompanionResource.class)
class RepositorySnapshotConsumerTest extends RepositoryFixture {
    @InjectKafkaCompanion KafkaCompanion companion;
    @Inject ObjectMapper mapper;

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
