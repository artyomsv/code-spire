package dev.codespire.orchestrator.repository;

import dev.codespire.contract.event.RepositoryRegistration;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/** Bounded bridge sweep for actual history, including repositories first seen under a legacy org hook. */
@ApplicationScoped
public class RepositoryHistoryBridge {
    private static final String LINK_REVIEW_STATUS = """
            UPDATE review_status SET repository_id=
              (SELECT repository_id FROM repository_registration_bridge WHERE registration_id=?)
            WHERE repository_id IS NULL AND provider_type=? AND workspace=? AND slug=?
            """;
    private static final String LINK_FACTORY_RUN = """
            UPDATE factory_run SET repository_id=
              (SELECT repository_id FROM repository_registration_bridge WHERE registration_id=?)
            WHERE repository_id IS NULL AND provider_type=? AND workspace=? AND slug=?
            """;

    @Inject DataSource dataSource;
    @Inject RepositoryMigrationBridge bridge;

    @Scheduled(every = "${spire.repository-history-interval:30s}", delayed = "15s", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void sweep() {
        List<History> histories = pending();
        for (History history : histories) importHistory(history);
    }

    private List<History> pending() {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT DISTINCT provider_type,workspace,slug FROM (
                  SELECT provider_type,workspace,slug FROM review_status WHERE repository_id IS NULL AND provider_type<>''
                  UNION SELECT provider_type,workspace,slug FROM factory_run WHERE repository_id IS NULL
                ) h WHERE NOT EXISTS (SELECT 1 FROM repository_registration_bridge b
                  WHERE b.provider_type=h.provider_type AND b.target=h.workspace || '/' || h.slug AND b.problem IS NOT NULL)
                ORDER BY provider_type,workspace,slug LIMIT 100
                """); ResultSet rows = statement.executeQuery()) {
            List<History> histories = new ArrayList<>();
            while (rows.next()) histories.add(new History(rows.getString(1), rows.getString(2), rows.getString(3)));
            return histories;
        } catch (SQLException failure) { throw new IllegalStateException("Cannot enumerate repository history", failure); }
    }

    @Transactional
    public void importHistory(History history) {
        UUID id = UUID.nameUUIDFromBytes(("repository-history:" + history.type() + ":" + history.workspace() + "/" + history.slug())
                .getBytes(StandardCharsets.UTF_8));
        bridge.apply(new RepositoryRegistration(id, 1, history.type(), origin(history), "repo",
                history.workspace() + "/" + history.slug(), true, false));
        try (Connection connection = dataSource.getConnection()) {
            link(connection, LINK_REVIEW_STATUS, new HistoryLink(id, history));
            link(connection, LINK_FACTORY_RUN, new HistoryLink(id, history));
        } catch (SQLException failure) { throw new IllegalStateException("Cannot link repository history", failure); }
    }

    private String origin(History history) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT DISTINCT html_url FROM review_status WHERE provider_type=? AND workspace=? AND slug=?
                """)) {
            statement.setString(1, history.type()); statement.setString(2, history.workspace()); statement.setString(3, history.slug());
            java.util.Set<String> origins = new java.util.HashSet<>();
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String url = rows.getString(1);
                    if (url == null || url.isBlank()) return null;
                    origins.add(dev.codespire.orchestrator.provider.ProviderClients.repositoryForgeOrigin(history.type(), url));
                }
            }
            return origins.size() == 1 ? origins.iterator().next() : null;
        } catch (IllegalArgumentException invalidUrl) { return null; }
        catch (SQLException failure) { throw new IllegalStateException("Cannot read repository origin evidence", failure); }
    }

    private void link(Connection connection, String sql, HistoryLink link) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setObject(1, link.registrationId()); statement.setString(2, link.history().type());
            statement.setString(3, link.history().workspace()); statement.setString(4, link.history().slug());
            statement.executeUpdate();
        }
    }

    public record History(String type, String workspace, String slug) { }
    private record HistoryLink(UUID registrationId, History history) { }
}
