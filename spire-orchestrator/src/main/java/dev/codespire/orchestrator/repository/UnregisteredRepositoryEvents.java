package dev.codespire.orchestrator.repository;

import dev.codespire.contract.event.RepositoryDelivery;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/** Durable metadata only; no payload or credentials, and no automatic enrollment. */
@ApplicationScoped
public class UnregisteredRepositoryEvents {
    @Inject DataSource dataSource;
    @Inject dev.codespire.orchestrator.attention.AttentionBroadcaster attention;

    public void record(RepositoryDelivery delivery) {
        if (delivery.registrationId() == null) return;
        String fullPath = delivery.repo().full();
        int leaf = fullPath.lastIndexOf('/');
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO repository_unregistered_event(registration_id,scm_type,forge_origin,workspace,slug)
                VALUES (?,?,?,?,?) ON CONFLICT (registration_id,scm_type,forge_origin,workspace,slug)
                DO UPDATE SET last_seen_at=now()
                """)) {
            ps.setObject(1, delivery.registrationId());
            ps.setString(2, delivery.providerType());
            ps.setString(3, delivery.forgeOrigin());
            ps.setString(4, fullPath.substring(0, leaf));
            ps.setString(5, fullPath.substring(leaf + 1));
            ps.executeUpdate();
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot record unregistered repository delivery", failure);
        }
        attention.refresh();
    }
}
