package dev.codespire.orchestrator.factory;

import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.RepositoryPermission;
import dev.codespire.orchestrator.provider.ProviderClients;
import dev.codespire.orchestrator.provider.ProviderRole;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.repository.RepositoryAccounts;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;

/** Fresh reviewer-only authority, with short database reads on either side of the bounded network call. */
@ApplicationScoped
public class FixPermissionService {
    @Inject DataSource dataSource;
    @Inject RepositoryAccounts accounts;
    @Inject ProviderClients clients;

    private record Version(long repository, UUID account, long credential, long override) {}
    private record Registration(RepoRef repository, Version version, FixAuthorization.Override override) {}
    private record Snapshot(Registration registration, ScmProvider account) {}

    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public FixAuthorization.Decision authorize(UUID repositoryId, String actorId) {
        return authorize(repositoryId,actorId,false);
    }

    /** A PR gate requires measured write access; an ALLOW override cannot substitute for it. */
    @Transactional(Transactional.TxType.NOT_SUPPORTED)
    public FixAuthorization.Decision authorizeApproval(UUID repositoryId,String actorId) {
        return authorize(repositoryId,actorId,true);
    }

    private FixAuthorization.Decision authorize(UUID repositoryId,String actorId,boolean measuredRequired) {
        RepositoryPermission unreadable = RepositoryPermission.unknown("Effective repository permission could not be read.");
        if (actorId == null || actorId.isBlank()) return FixAuthorization.decide(actorId, null, unreadable);
        try {
            Snapshot snapshot = QuarkusTransaction.requiringNew().call(() -> snapshot(repositoryId, actorId));
            if (snapshot == null) return unavailableRepository();
            FixAuthorization.Override override = snapshot.registration().override();
            if (override != null && (!measuredRequired || override==FixAuthorization.Override.DENY))
                return FixAuthorization.decide(actorId, override, unreadable);

            // The short transaction has committed and released its connection. Saves and other
            // permission reads can proceed while the forge is slow; no database lock crosses this call.
            RepositoryPermission permission = measure(snapshot.account(), snapshot.registration().repository(), actorId);
            Registration current = QuarkusTransaction.requiringNew().call(() -> registration(repositoryId, actorId));
            if (current == null || !snapshot.registration().version().equals(current.version())) {
                return new FixAuthorization.Decision(false, FixAuthorization.Reason.PERMISSION_UNAVAILABLE,
                        "Repository, reviewer credential or /fix overrides changed during permission lookup; retry the command.");
            }
            // Explicit overrides already returned above; only the measured fallback reaches here.
            return FixAuthorization.decide(actorId, null, permission);
        } catch (RuntimeException failure) {
            return new FixAuthorization.Decision(false, FixAuthorization.Reason.PERMISSION_UNAVAILABLE, unreadable.detail());
        }
    }

    private Snapshot snapshot(UUID repositoryId, String actorId) {
        Registration registration = registration(repositoryId, actorId);
        if (registration == null) return null;
        Optional<ScmProvider> account = accounts.resolve(repositoryId, ProviderRole.REVIEWER);
        return account.map(value -> new Snapshot(registration, value)).orElse(null);
    }

    private Registration registration(UUID repositoryId, String actorId) {
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement("""
                SELECT r.workspace,r.slug,r.revision AS repository_revision,p.id AS account_id,
                       p.revision AS account_revision,COALESCE(f.revision,0) AS actor_revision,f.effect
                FROM repository r
                JOIN repository_account b ON b.repository_id=r.id AND b.role='REVIEWER'
                JOIN scm_provider p ON p.id=b.account_id
                LEFT JOIN repository_fix_actor f ON f.repository_id=r.id AND f.actor_id=?
                WHERE r.id=?
                """)) {
            statement.setString(1, actorId);
            statement.setObject(2, repositoryId);
            try (ResultSet rows = statement.executeQuery()) {
                if (!rows.next()) return null;
                String effect = rows.getString("effect");
                return new Registration(new RepoRef(rows.getString("workspace"), rows.getString("slug")),
                        new Version(rows.getLong("repository_revision"), rows.getObject("account_id", UUID.class),
                                rows.getLong("account_revision"), rows.getLong("actor_revision")),
                        effect == null ? null : FixAuthorization.Override.valueOf(effect));
            }
        } catch (SQLException failure) {
            throw new IllegalStateException("Cannot read repository permission revision", failure);
        }
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
