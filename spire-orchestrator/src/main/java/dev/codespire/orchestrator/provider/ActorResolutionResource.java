package dev.codespire.orchestrator.provider;

import dev.codespire.contract.port.ActorDirectory;
import dev.codespire.contract.scm.ResolvedActor;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.repository.RepositoryView;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

/** Person inputs are resolved again under the selected account lock before any policy write. */
@Path("/api/providers/{account}/actors")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ActorResolutionResource {
    @Inject ProviderRegistry providers;
    @Inject ProviderClients clients;
    @Inject RepositoryRegistry repositories;
    @Inject ActorPolicyRegistry policies;
    @Inject DataSource dataSource;

    public record Input(String handle, String providerUserId, UUID repositoryId, long revision, String effect) {}

    @POST @Path("/resolve")
    @RolesAllowed("spire-admin")
    public ActorDirectory.Result resolve(@PathParam("account") UUID account, Input input) {
        ScmProvider selected = selected(account);
        return response(clients.actorDirectory(selected).lookup(input.handle(), scope(selected, input.repositoryId())));
    }

    @GET
    @RolesAllowed("spire-admin")
    public ActorPolicyRegistry.Policy list(@PathParam("account") UUID account, @QueryParam("refresh") @DefaultValue("false") boolean refresh) {
        ActorPolicyRegistry.Policy policy = policies.account(account);
        if (!refresh) return policy;
        return new ActorPolicyRegistry.Policy(policy.revision(), refresh(selected(account), policy.actors(), account, false));
    }

    @POST @Transactional
    @RolesAllowed("spire-admin")
    public ActorPolicyRegistry.Policy save(@PathParam("account") UUID account, Input input) {
        lockAccount(account);
        ScmProvider selected = selected(account);
        ResolvedActor actor = verify(selected, input, scope(selected, input.repositoryId()));
        try { policies.saveAccount(account, actor, input.revision()); }
        catch (ActorPolicyRegistry.Conflict failure) { throw conflict(failure); }
        return policies.account(account);
    }

    @DELETE @Path("/{actor}")
    @RolesAllowed("spire-admin")
    public ActorPolicyRegistry.Policy delete(@PathParam("account") UUID account, @PathParam("actor") String actor, @QueryParam("revision") long revision) {
        try { policies.deleteAccount(account, actor, revision); }
        catch (ActorPolicyRegistry.Conflict failure) { throw conflict(failure); }
        return policies.account(account);
    }


    public ActorDirectory.Result resolveRepository(@PathParam("repository") UUID repository, Input input) {
        RepositoryView repo = repository(repository);
        return response(clients.actorDirectory(reviewer(repo)).lookup(input.handle(), repo.workspace()));
    }


    public List<ActorDisplay> repositoryActors(@PathParam("repository") UUID repository,
                                              @QueryParam("refresh") @DefaultValue("false") boolean refresh) {
        RepositoryView repo = repository(repository);
        List<ActorDisplay> actors = policies.repository(repository);
        return refresh ? refresh(reviewer(repo), actors, repository, true) : actors;
    }

    @Transactional
    public List<ActorDisplay> saveRepository(@PathParam("repository") UUID repository, Input input) {
        lockRepository(repository);
        RepositoryView repo = repository(repository);
        ScmProvider selected = reviewer(repo);
        lockAccount(selected.id());
        selected = reviewer(repo);
        if (!List.of("ALLOW", "DENY").contains(input.effect() == null ? "" : input.effect())) {
            throw error(422, "Choose Allow or Deny for this person.");
        }
        ResolvedActor actor = verify(selected, input, repo.workspace());
        try { policies.saveRepository(repository, selected.id(), repo.revision(), actor, input.effect(), input.revision()); }
        catch (ActorPolicyRegistry.Conflict failure) { throw conflict(failure); }
        return policies.repository(repository);
    }


    public List<ActorDisplay> deleteRepository(@PathParam("repository") UUID repository, @PathParam("actor") String actor,
                                               @QueryParam("revision") long revision) {
        try { policies.deleteRepository(repository, actor, revision); }
        catch (ActorPolicyRegistry.Conflict failure) { throw conflict(failure); }
        return policies.repository(repository);
    }

    private ResolvedActor verify(ScmProvider account, Input input, String scope) {
        ActorDirectory directory = clients.actorDirectory(account);
        ActorDirectory.Result result = response(directory.lookup(input.handle(), scope));
        // A submitted id is never proof: repeat lookup and bind it to the exact returned candidate.
        ResolvedActor actor = result.actors().stream().filter(value -> value.providerUserId().equals(input.providerUserId()))
                .findFirst().orElseThrow(() -> error(422, "The selected identity changed. Resolve and select the person again."));
        ActorDirectory.Result checked = response(directory.byId(actor.providerUserId()));
        if (checked.status() != ActorDirectory.Status.FOUND || checked.actors().size() != 1
                || !actor.providerUserId().equals(checked.actors().getFirst().providerUserId())) {
            throw error(422, "The forge did not confirm the selected stable identity.");
        }
        return checked.actors().getFirst();
    }

    private List<ActorDisplay> refresh(ScmProvider account, List<ActorDisplay> stored, UUID owner, boolean repository) {
        ActorDirectory directory = clients.actorDirectory(account);
        List<ActorDisplay> refreshed = new ArrayList<>();
        for (ActorDisplay old : stored) {
            ActorDirectory.Result result = directory.byId(old.providerUserId());
            if (result.status() == ActorDirectory.Status.FOUND && result.actors().size() == 1
                    && old.providerUserId().equals(result.actors().getFirst().providerUserId())) {
                ResolvedActor actor = result.actors().getFirst();
                if (repository) policies.refreshRepository(owner, actor); else policies.refreshAccount(owner, actor);
                refreshed.add(new ActorDisplay(old.providerUserId(), actor.handle(), actor.displayName(), java.time.Instant.now(), false, old.effect(), old.revision()));
            } else {
                if (repository) policies.failedRepositoryRefresh(owner, old.providerUserId());
                else policies.failedAccountRefresh(owner, old.providerUserId());
                refreshed.add(new ActorDisplay(old.providerUserId(), old.handle(), old.displayName(), old.resolvedAt(), true, old.effect(), old.revision()));
            }
        }
        return refreshed;
    }

    private ScmProvider selected(UUID account) {
        ScmProvider provider = providers.resolveById(account).orElseThrow(NotFoundException::new);
        if (!provider.enabled()) throw error(409, "The selected account is disabled. Enable it before resolving people.");
        return provider;
    }
    private RepositoryView repository(UUID id) { return repositories.get(id).orElseThrow(NotFoundException::new); }
    private ScmProvider reviewer(RepositoryView repository) {
        if (repository.reviewer() == null) throw error(409, "Select a reviewer account before resolving people.");
        return selected(repository.reviewer().id());
    }
    private String scope(ScmProvider account, UUID repositoryId) {
        if (repositoryId == null) return null;
        RepositoryView repo = repository(repositoryId);
        if (!repo.scmType().equals(account.type()) || !repo.forgeOrigin().equals(dev.codespire.contract.scm.ForgeOrigin.of(account.baseUrl()))
                || (repo.reviewer() == null || !repo.reviewer().id().equals(account.id()))
                    && (repo.factory() == null || !repo.factory().id().equals(account.id()))) {
            throw error(422, "Select a repository bound to this account and forge origin.");
        }
        return repo.workspace();
    }
    private static ActorDirectory.Result response(ActorDirectory.Result result) {
        return switch (result.status()) {
            case FOUND, SELECTION_REQUIRED -> result;
            case UNAVAILABLE -> throw error(503, result.detail());
            case NOT_FOUND, AMBIGUOUS, UNSUPPORTED -> throw error(422, result.detail());
        };
    }
    private void lockAccount(UUID id) { lock("SELECT id FROM scm_provider WHERE id=? FOR UPDATE", id); }
    private void lockRepository(UUID id) { lock("SELECT id FROM repository WHERE id=? FOR UPDATE", id); }
    private void lock(String sql, UUID id) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, id);
            try (java.sql.ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new NotFoundException(); }
        } catch (SQLException failure) { throw new IllegalStateException("Could not lock actor policy", failure); }
    }
    private static WebApplicationException conflict(ActorPolicyRegistry.Conflict failure) { return error(409, failure.getMessage()); }
    private static WebApplicationException error(int status, String detail) {
        return new WebApplicationException(Response.status(status).entity(java.util.Map.of("error", detail)).build());
    }
}
