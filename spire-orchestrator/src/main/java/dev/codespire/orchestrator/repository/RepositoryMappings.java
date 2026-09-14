package dev.codespire.orchestrator.repository;

import dev.codespire.contract.scm.ForgeOrigin;
import dev.codespire.orchestrator.provider.ProviderRegistry.AccountConflict;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/** Explicit repair of an ambiguous legacy registration; never guesses a host or grants a role. */
@ApplicationScoped
public class RepositoryMappings {
    @Inject DataSource dataSource;
    @Inject RepositoryRegistry repositories;

    public List<Pending> pending() {
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                SELECT registration_id,revision,provider_type,forge_origin,target,problem
                FROM repository_registration_bridge WHERE problem IS NOT NULL AND NOT deleted ORDER BY target,registration_id
                """); var rows = statement.executeQuery()) {
            List<Pending> result = new ArrayList<>();
            while (rows.next()) result.add(new Pending(rows.getObject(1, UUID.class), rows.getLong(2),
                    rows.getString(3), rows.getString(4), rows.getString(5), rows.getString(6)));
            return result;
        } catch (SQLException failure) { throw new IllegalStateException("Cannot read pending repository mappings", failure); }
    }

    @Transactional
    public void link(UUID registration, long revision, UUID repository) {
        RepositoryView selected = repositories.get(repository).orElseThrow(() -> new AccountConflict("Register the repository first"));
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement("""
                SELECT provider_type,forge_origin,target FROM repository_registration_bridge
                WHERE registration_id=? AND revision=? AND NOT deleted AND problem IS NOT NULL FOR UPDATE
                """)) {
            statement.setObject(1, registration); statement.setLong(2, revision);
            try (var rows = statement.executeQuery()) {
                if (!rows.next()) throw new AccountConflict("Registration changed; reload before linking");
                if (!rows.getString(1).equals(selected.scmType())
                        || !rows.getString(3).equals(selected.workspace() + "/" + selected.slug())
                        || rows.getString(2) != null && !ForgeOrigin.of(rows.getString(2)).equals(selected.forgeOrigin())) {
                    throw new AccountConflict("Selected repository does not match the registration coordinates");
                }
            }
            try (var update = connection.prepareStatement("UPDATE repository_registration_bridge SET repository_id=?,problem=NULL WHERE registration_id=?")) {
                update.setObject(1, repository); update.setObject(2, registration); update.executeUpdate();
            }
        } catch (SQLException failure) { throw new IllegalStateException("Cannot link repository registration", failure); }
    }

    public record Pending(UUID registrationId, long revision, String scmType, String forgeOrigin, String target, String problem) { }
    public record Selection(UUID repositoryId, long revision) { }
}
