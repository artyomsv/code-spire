package dev.codespire.orchestrator.factory;

import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.RepositoryPermission;
import dev.codespire.orchestrator.provider.ActorPolicyRegistry;
import dev.codespire.orchestrator.provider.ProviderClients;
import dev.codespire.orchestrator.provider.ProviderRole;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.repository.RepositoryAccounts;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;

/** Each decision reads current authority using only the repository's assigned reviewer credential. */
@ApplicationScoped
public class FixPermissionService {
    @Inject DataSource dataSource;
    @Inject RepositoryAccounts accounts;
    @Inject ProviderClients clients;
    @Inject ActorPolicyRegistry actors;

    @Transactional
    public FixAuthorization.Decision authorize(UUID repositoryId, String actorId) {
        RepositoryPermission unreadable = RepositoryPermission.unknown("Effective repository permission could not be read.");
        if (actorId == null || actorId.isBlank()) return FixAuthorization.decide(actorId, null, unreadable);
        // Hold the same rows as repository/account edits across the bounded read. An account can have
        // its token rotated or be rebound, but that change cannot split one permission decision.
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT r.workspace,r.slug FROM repository r
                JOIN repository_account b ON b.repository_id=r.id AND b.role='REVIEWER'
                JOIN scm_provider p ON p.id=b.account_id
                WHERE r.id=? FOR UPDATE OF r,p
                """)) {
            statement.setObject(1, repositoryId);
            RepoRef repository;
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return unavailableRepository();
                repository = new RepoRef(rows.getString(1), rows.getString(2));
            }
            Optional<ScmProvider> account = accounts.resolve(repositoryId, ProviderRole.REVIEWER);
            if (account.isEmpty()) return unavailableRepository();
            FixAuthorization.Override override = override(repositoryId, actorId);
            if (override != null) return FixAuthorization.decide(actorId, override, unreadable);
            RepositoryPermission permission = measure(account.get(), repository, actorId);
            return FixAuthorization.decide(actorId, null, permission);
        } catch (SQLException | RuntimeException failure) {
            return new FixAuthorization.Decision(false, FixAuthorization.Reason.PERMISSION_UNAVAILABLE, unreadable.detail());
        }
    }

    private FixAuthorization.Override override(UUID repositoryId, String actorId) {
        return actors.repository(repositoryId).stream().filter(actor -> actorId.equals(actor.providerUserId()))
                .map(actor -> FixAuthorization.Override.valueOf(actor.effect())).findFirst().orElse(null);
    }

    RepositoryPermission measure(ScmProvider account, RepoRef repository, String actorId) {
        FutureTask<RepositoryPermission> lookup = new FutureTask<>(() -> clients.repositoryPermissionSource(account).permission(repository, actorId));
        Thread.ofVirtual().name("fix-permission").start(lookup);
        try {
            // Includes identity resolution, redirects and every page. No retry or stale-positive cache.
            return lookup.get(20, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            return RepositoryPermission.unknown("Effective repository permission lookup was interrupted.");
        } catch (ExecutionException | TimeoutException failure) {
            return RepositoryPermission.unknown("Effective repository permission lookup failed or exceeded its 20-second budget.");
        } finally { lookup.cancel(true); }
    }

    private static FixAuthorization.Decision unavailableRepository() {
        return new FixAuthorization.Decision(false, FixAuthorization.Reason.REPOSITORY_UNAVAILABLE,
                "The repository has no usable selected reviewer account.");
    }
}
