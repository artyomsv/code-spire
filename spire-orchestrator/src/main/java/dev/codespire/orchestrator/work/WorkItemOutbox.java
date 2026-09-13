package dev.codespire.orchestrator.work;

import dev.codespire.encryption.EncryptionService;
import dev.codespire.orchestrator.pipeline.KafkaSends;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/** At-least-once domain notifications, with stable event ids. No run command is emitted by admission. */
@ApplicationScoped
public class WorkItemOutbox {
    @Inject DataSource dataSource;
    @Inject EncryptionService encryption;
    @Inject @Channel("work-events-out") Emitter<String> emitter;
    private record Pending(UUID id, String item, byte[] payload) {}

    @Scheduled(every="${spire.work-outbox-interval:2s}", concurrentExecution=Scheduled.ConcurrentExecution.SKIP)
    public void publish() {
        List<Pending> pending = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT effect_id,work_item_id,payload FROM work_item_outbox WHERE published_at IS NULL ORDER BY created_at LIMIT 50");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) pending.add(new Pending(rs.getObject(1, UUID.class), rs.getString(2), rs.getBytes(3)));
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        for (Pending effect : pending) {
            String payload = new String(encryption.decrypt(effect.payload(), "work-effect:" + effect.id()), StandardCharsets.UTF_8);
            KafkaSends.sendAndAwait(emitter, effect.item(), payload, "Work event");
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                    "UPDATE work_item_outbox SET published_at=now() WHERE effect_id=? AND published_at IS NULL")) {
                ps.setObject(1, effect.id()); ps.executeUpdate();
            } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        }
    }
}
