package dev.codespire.orchestrator.work;

import dev.codespire.contract.port.ActorDirectory;
import dev.codespire.contract.scm.ForgeOrigin;
import dev.codespire.contract.scm.ResolvedActor;
import dev.codespire.orchestrator.provider.*;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.repository.RepositoryView;
import dev.codespire.worksource.WorkSourceType;
import io.quarkus.narayana.jta.QuarkusTransaction;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import javax.sql.DataSource;

/** Explicit source/account/repository selection; external verification holds no database lock. */
@ApplicationScoped
public class WorkSourceAdministration {
    @Inject DataSource dataSource;
    @Inject WorkSourceRegistry sources;
    @Inject ProviderRegistry providers;
    @Inject ProviderClients clients;
    @Inject RepositoryRegistry repositories;
    public record Input(String name, WorkSourceType type, String origin, String scope, UUID repositoryId, UUID accountId, boolean enabled) {}
    public record ActorInput(String handle, String providerUserId, long revision) {}
    public record Edit(String name, UUID accountId, boolean enabled, long revision) {}
    private record Authority(long repository, long account) {}

    public WorkSourceRegistry.Source create(Input input) {
        if (input == null || input.name() == null || input.name().isBlank() || input.type() == null)
            throw new IllegalArgumentException("Name and source type are required");
        Authority before = authority(input.repositoryId(), input.accountId());
        ScmProvider account = selected(input.type(), input.origin(), input.accountId());
        RepositoryView repository = repositories.get(input.repositoryId()).orElseThrow();
        if (!repository.enabled() || !clients.workScopeMatchesRepository(input.type(), input.origin(), input.scope(), repository.scmType(),
                repository.forgeOrigin(), repository.workspace() + "/" + repository.slug()))
            throw new IllegalArgumentException("The source scope must agree with its target repository");
        String projectId = bounded(() -> clients.workProjectId(input.type(), account, input.scope()));
        UUID id = UUID.randomUUID();
        return QuarkusTransaction.requiringNew().call(() -> {
            if (!before.equals(lockAuthority(input.repositoryId(), input.accountId()))) throw new IllegalArgumentException("Account or repository changed; retry registration");
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO work_source(id,name,type,origin,external_project_id,external_scope,repository_id,account_id,enabled)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """)) {
                ps.setObject(1, id); ps.setString(2, input.name().trim()); ps.setString(3, input.type().name());
                ps.setString(4, ForgeOrigin.of(input.origin())); ps.setString(5, projectId); ps.setString(6, input.scope());
                ps.setObject(7, input.repositoryId()); ps.setObject(8, input.accountId()); ps.setBoolean(9, input.enabled()); ps.executeUpdate();
                return sources.get(id).orElseThrow();
            } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        });
    }

    public WorkSourceRegistry.Source edit(UUID id, Edit input) {
        WorkSourceRegistry.Source source = sources.get(id).orElseThrow();
        if (input == null || input.name() == null || input.name().isBlank()) throw new IllegalArgumentException("Source name is required");
        Authority before = authority(source.repositoryId(), input.accountId());
        selected(source.type(), source.origin(), input.accountId());
        return QuarkusTransaction.requiringNew().call(() -> {
            if (!before.equals(lockAuthority(source.repositoryId(), input.accountId()))) throw new IllegalArgumentException("Account changed; reload the source");
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                    "UPDATE work_source SET name=?,account_id=?,enabled=?,revision=revision+1 WHERE id=? AND revision=?")) {
                ps.setString(1, input.name().trim()); ps.setObject(2, input.accountId()); ps.setBoolean(3, input.enabled());
                ps.setObject(4, id); ps.setLong(5, input.revision());
                if (ps.executeUpdate() != 1) throw new IllegalArgumentException("Source changed; reload it");
                return sources.get(id).orElseThrow();
            } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        });
    }

    public WorkSourceRegistry.Source saveActor(UUID id, ActorInput input) {
        WorkSourceRegistry.Source source = sources.get(id).orElseThrow();
        ScmProvider account = selected(source.type(), source.origin(), source.accountId());
        ResolvedActor actor = bounded(() -> {
            ActorDirectory directory = clients.actorDirectory(account);
            ActorDirectory.Result candidates = directory.lookup(input.handle(), source.repository().workspace());
            if (candidates.actors().stream().noneMatch(candidate -> candidate.providerUserId().equals(input.providerUserId())))
                throw new IllegalArgumentException("Resolve and select the person again");
            ActorDirectory.Result confirmed = directory.byId(input.providerUserId());
            if (confirmed.status() != ActorDirectory.Status.FOUND || confirmed.actors().size() != 1
                    || !input.providerUserId().equals(confirmed.actors().getFirst().providerUserId()))
                throw new IllegalArgumentException("The tracker did not confirm the selected person");
            return confirmed.actors().getFirst();
        });
        return QuarkusTransaction.requiringNew().call(() -> {
            try (Connection c = dataSource.getConnection()) {
                WorkSourceRegistry.Source current = sources.get(c, id, true).orElseThrow();
                if (!current.enabled() || !source.version().equals(current.version()) || input.revision() != current.version().source())
                    throw new IllegalArgumentException("Source authority changed; resolve the person again");
                try (PreparedStatement ps = c.prepareStatement("""
                        INSERT INTO work_source_actor(source_id,actor_id,observed_handle,display_name,resolved_at) VALUES (?,?,?,?,now())
                        ON CONFLICT(source_id,actor_id) DO UPDATE SET observed_handle=excluded.observed_handle,
                        display_name=excluded.display_name,resolved_at=excluded.resolved_at
                        """)) {
                    ps.setObject(1, id); ps.setString(2, actor.providerUserId()); ps.setString(3, actor.handle());
                    ps.setString(4, actor.displayName()); ps.executeUpdate();
                }
                bump(c, id);
                return sources.get(c, id, false).orElseThrow();
            } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        });
    }

    public WorkSourceRegistry.Source removeActor(UUID id, String actor, long revision) {
        return QuarkusTransaction.requiringNew().call(() -> {
            try (Connection c = dataSource.getConnection()) {
                WorkSourceRegistry.Source current = sources.get(c, id, true).orElseThrow();
                if (current.version().source() != revision) throw new IllegalArgumentException("Source changed; reload it");
                try (PreparedStatement ps = c.prepareStatement("DELETE FROM work_source_actor WHERE source_id=? AND actor_id=?")) {
                    ps.setObject(1, id); ps.setString(2, actor); ps.executeUpdate();
                }
                bump(c, id);
                return sources.get(c, id, false).orElseThrow();
            } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        });
    }
    private void bump(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE work_source SET revision=revision+1 WHERE id=?")) {
            ps.setObject(1, id); ps.executeUpdate();
        }
    }
    private ScmProvider selected(WorkSourceType type, String origin, UUID id) {
        ScmProvider account = providers.resolveById(id).orElseThrow();
        if (!account.enabled() || !clients.compatibleWorkAccount(type, account) || !ForgeOrigin.of(origin).equals(ForgeOrigin.of(account.baseUrl())))
            throw new IllegalArgumentException("Select an enabled, compatible account on the source origin");
        return account;
    }
    private Authority authority(UUID repository, UUID account) { return authority(repository, account, false); }
    private Authority lockAuthority(UUID repository, UUID account) { return authority(repository, account, true); }
    private Authority authority(UUID repository, UUID account, boolean lock) {
        String sql = lock ? "SELECT r.revision,a.revision FROM repository r CROSS JOIN scm_provider a WHERE r.id=? AND a.id=? FOR UPDATE OF r,a"
                : "SELECT r.revision,a.revision FROM repository r CROSS JOIN scm_provider a WHERE r.id=? AND a.id=?";
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, repository); ps.setObject(2, account);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Repository and account are required");
                return new Authority(rs.getLong(1), rs.getLong(2));
            }
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }
    private static <T> T bounded(Callable<T> action) {
        FutureTask<T> task = new FutureTask<>(action);
        Thread.ofVirtual().start(task);
        try { return task.get(20, TimeUnit.SECONDS); }
        catch (Exception failure) {
            task.cancel(true);
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            throw new IllegalArgumentException("Tracker verification failed; check the account and scope");
        }
    }
}
