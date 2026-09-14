package dev.codespire.gateway.registry;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;

/** Broker acknowledgement precedes sent_at; a crash in between redelivers the same revision safely. */
@ApplicationScoped
public class RepositorySnapshotPublisher {
    @Inject DataSource dataSource;
    @Inject @Channel("registry-out") Emitter<String> emitter;

    @Scheduled(every = "${spire.repository-publish-interval:5s}", delayed = "15s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void publishPending() throws Exception {
        for (Snapshot snapshot : pending()) {
            send(snapshot).get(10, TimeUnit.SECONDS);
            markSent(snapshot.revision());
        }
    }

    CompletableFuture<Void> send(Snapshot snapshot) {
        CompletableFuture<Void> acknowledgement = new CompletableFuture<>();
        emitter.send(Message.of(snapshot.payload(), Metadata.of(OutgoingKafkaRecordMetadata.<String>builder()
                .withKey(snapshot.registrationId()).build()),
                () -> { acknowledgement.complete(null); return CompletableFuture.completedFuture(null); },
                failure -> { acknowledgement.completeExceptionally(failure); return CompletableFuture.completedFuture(null); }));
        return acknowledgement;
    }

    List<Snapshot> pending() throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "SELECT revision,registration_id,payload FROM repository_snapshot_outbox WHERE sent_at IS NULL ORDER BY revision LIMIT 100");
             ResultSet rows = statement.executeQuery()) {
            List<Snapshot> snapshots = new ArrayList<>();
            while (rows.next()) snapshots.add(new Snapshot(rows.getLong(1), rows.getString(2), rows.getString(3)));
            return snapshots;
        }
    }

    void markSent(long revision) throws SQLException {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(
                "UPDATE repository_snapshot_outbox SET sent_at=now() WHERE revision=? AND sent_at IS NULL")) {
            statement.setLong(1, revision); statement.executeUpdate();
        }
    }

    record Snapshot(long revision, String registrationId, String payload) { }
}
