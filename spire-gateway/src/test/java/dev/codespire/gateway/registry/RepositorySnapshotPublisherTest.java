package dev.codespire.gateway.registry;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.RepositoryRegistration;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kafka.InjectKafkaCompanion;
import io.quarkus.test.kafka.KafkaCompanionResource;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@QuarkusTestResource(KafkaCompanionResource.class)
class RepositorySnapshotPublisherTest {
    @Inject DataSource dataSource;
    @Inject WebhookRepoRegistry registry;
    @Inject RepositorySnapshotPublisher publisher;
    @Inject ObjectMapper mapper;
    @InjectKafkaCompanion KafkaCompanion companion;
    UUID registrationId;

    WebhookRepoSecret create() {
        var created = registry.create(new WebhookRepoInput("gitlab", "repo", "TEST-group/" + UUID.randomUUID(), true));
        registrationId = UUID.fromString(created.repo().id());
        return created;
    }
    List<RepositorySnapshotPublisher.Snapshot> ownPending() throws Exception {
        try (var c = dataSource.getConnection(); var ps = c.prepareStatement(
                "SELECT revision,payload FROM repository_snapshot_outbox WHERE registration_id=? AND sent_at IS NULL ORDER BY revision")) {
            ps.setObject(1, registrationId);
            try (var rows = ps.executeQuery()) {
                var result = new java.util.ArrayList<RepositorySnapshotPublisher.Snapshot>();
                while (rows.next()) result.add(new RepositorySnapshotPublisher.Snapshot(rows.getLong(1), registrationId.toString(), rows.getString(2)));
                return result;
            }
        }
    }

    @AfterEach void removeFixture() throws Exception {
        if (registrationId == null) return;
        registry.delete(registrationId);
        try (var c = dataSource.getConnection(); var ps = c.prepareStatement("DELETE FROM repository_snapshot_outbox WHERE registration_id=?")) {
            ps.setObject(1, registrationId); ps.executeUpdate();
        }
    }

    @Test void sendsOnlyMetadataWithStableKeyThroughTheBroker() throws Exception {
        var created = create();
        var pending = ownPending().getFirst();
        var snapshot = mapper.readValue(pending.payload(), RepositoryRegistration.class);
        assertEquals(registrationId, snapshot.registrationId());
        assertFalse(pending.payload().contains(created.secret()));
        assertFalse(pending.payload().contains(created.repo().webhookKey()));
        assertEquals(12, mapper.readTree(pending.payload()).size());
        try (var consumed = companion.consumeStrings().withGroupId("TEST-registry-" + registrationId)
                .fromTopics("cs.registry-integration", Duration.ofSeconds(5))) {
            publisher.send(pending).get(15, java.util.concurrent.TimeUnit.SECONDS);
            consumed.awaitCompletion(Duration.ofSeconds(15));
            assertTrue(consumed.getRecords().stream().anyMatch(record -> record.key().equals(registrationId.toString())
                    && record.value().equals(pending.payload())));
        }
    }

    @Test void failedBrokerAcknowledgementKeepsTheOutboxForRetry() throws Exception {
        create();
        var original = ownPending().getFirst();
        var failed = new RepositorySnapshotPublisher() {
            @Override List<Snapshot> pending() { return List.of(original); }
            @Override CompletableFuture<Void> send(Snapshot snapshot) { return CompletableFuture.failedFuture(new IllegalStateException("TEST-broker-down")); }
        };
        failed.dataSource = dataSource;
        assertThrows(ExecutionException.class, failed::publishPending);
        assertEquals(List.of(original), ownPending());
        var restarted = new RepositorySnapshotPublisher() {
            @Override List<Snapshot> pending() { return List.of(original); }
            @Override CompletableFuture<Void> send(Snapshot snapshot) { return CompletableFuture.completedFuture(null); }
        };
        restarted.dataSource = dataSource;
        restarted.publishPending();
        assertTrue(ownPending().isEmpty());
    }

    @Test void updateAndDeletionHaveIncreasingDurableRevisions() throws Exception {
        var created = create();
        registry.update(registrationId, new WebhookRepoInput("gitlab", "repo", created.repo().target(), false));
        registry.delete(registrationId);
        var rows = ownPending();
        assertEquals(3, rows.size());
        assertTrue(rows.get(0).revision() < rows.get(1).revision() && rows.get(1).revision() < rows.get(2).revision());
        assertTrue(mapper.readValue(rows.getLast().payload(), RepositoryRegistration.class).deleted());
        assertFalse(mapper.readValue(rows.get(1).payload(), RepositoryRegistration.class).enabled());
    }

    @Test void rollbackDoesNotPublishAnUncommittedRegistration() throws Exception {
        registrationId = UUID.randomUUID();
        try (var c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (var ps = c.prepareStatement("INSERT INTO webhook_repo (id,provider_type,scope,target,webhook_key,webhook_secret) VALUES (?,'gitlab','repo',? ,?,'TEST-cipher')")) {
                ps.setObject(1, registrationId); ps.setString(2, "TEST/" + registrationId); ps.setString(3, "TEST-" + registrationId); ps.executeUpdate();
            } finally { c.rollback(); c.setAutoCommit(true); }
        }
        assertTrue(ownPending().isEmpty());
    }

    @Test void preservesConfiguredOriginWhenALegacyClientEditsRegistration() throws Exception {
        var created = registry.create(new WebhookRepoInput("gitlab", "repo", "TEST-group/" + UUID.randomUUID(), true,
                "https://TEST-forge.example.test:443/api/v4"));
        registrationId = UUID.fromString(created.repo().id());
        registry.update(registrationId, new WebhookRepoInput("gitlab", "repo", created.repo().target(), false));
        assertEquals("https://test-forge.example.test", registry.get(registrationId).orElseThrow().forgeOrigin());
        for (var row : ownPending()) {
            assertEquals("https://test-forge.example.test", mapper.readValue(row.payload(), RepositoryRegistration.class).forgeOrigin());
        }
    }

    @Test void databaseRejectsBlankOriginButAllowsUnknownOrigin() throws Exception {
        create();
        assertNull(registry.get(registrationId).orElseThrow().forgeOrigin());
        try (java.sql.Connection connection = dataSource.getConnection();
             java.sql.PreparedStatement statement = connection.prepareStatement("UPDATE webhook_repo SET forge_origin='' WHERE id=?")) {
            statement.setObject(1, registrationId);
            java.sql.SQLException failure = assertThrows(java.sql.SQLException.class, statement::executeUpdate);
            assertEquals("23514", failure.getSQLState());
        }
        assertEquals(1, ownPending().size(), "a rejected update must not queue another snapshot");
    }
}
