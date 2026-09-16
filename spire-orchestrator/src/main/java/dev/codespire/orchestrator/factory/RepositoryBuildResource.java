package dev.codespire.orchestrator.factory;

import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.provider.ProviderClients;
import dev.codespire.orchestrator.provider.ProviderRole;
import dev.codespire.orchestrator.repository.RepositoryAccounts;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.repository.RepositoryView;
import dev.codespire.orchestrator.security.OidcSubjects;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.*;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The repository's build setup: what a prepared task copies when nobody types it (M3.5 part B).
 *
 * <p>The item-scoped endpoints in {@code WorkPreparationResource} cannot serve this screen. They need an
 * existing work item and pass its artifact evidence first, and setup happens before the first ticket
 * exists.
 */
@Path("/api/repositories/{repository}/factory")
@RolesAllowed("spire-admin")
@Produces(MediaType.APPLICATION_JSON) @Consumes(MediaType.APPLICATION_JSON)
public class RepositoryBuildResource {
    private static final org.jboss.logging.Logger LOG = org.jboss.logging.Logger.getLogger(RepositoryBuildResource.class);
    @Inject BuildDefaults defaults;
    @Inject FactoryConfig config;
    @Inject RepositoryRegistry repositories;
    @Inject RepositoryAccounts accounts;
    @Inject ProviderClients clients;
    @Inject SecurityIdentity identity;

    /** The harness names this deployment can actually run; a name without an agent image refuses at dispatch. */
    public record Options(List<String> harnesses) {}
    /** @param account the role whose account answered, so a reviewer-confirmed head is not read as factory push access */
    public record Head(String branch, String commit, String account) {}

    @GET @Path("/build")
    public BuildDefaults.Defaults get(@PathParam("repository") UUID repository) {
        repositories.get(repository).orElseThrow(NotFoundException::new);
        return defaults.get(repository);
    }

    @GET @Path("/build/options")
    public Options options() {
        return new Options(config.agentImage().keySet().stream().sorted().toList());
    }

    @PUT @Path("/build")
    public BuildDefaults.Defaults save(@PathParam("repository") UUID repository, BuildDefaults.Input input) {
        String actor = OidcSubjects.of(identity);
        if (actor.isBlank()) throw new ForbiddenException("A verified operator identity is required");
        try {
            return defaults.save(repository, input, actor);
        } catch (BuildDefaults.Refused refused) {
            throw refused(switch (refused.reason()) {
                case "repository_unknown" -> 404;
                case "build_defaults_changed" -> 409;
                default -> 400;
            }, refused.reason());
        }
    }

    /**
     * The current head of one branch, read through the repository's own account.
     *
     * <p>The FACTORY account first, then the REVIEWER, exactly as the item-scoped read resolves them
     * ({@code WorkPreparationResource.head}): reading a branch head needs no push right, and a
     * repository being set up may not have bound its factory account yet. The screen says which one
     * answered, so nobody reads a REVIEWER-confirmed head as proof that the factory can push.
     */
    @GET @Path("/branch-head")
    public Head head(@PathParam("repository") UUID repository, @QueryParam("branch") String branch) {
        if (branch == null || branch.isBlank()) throw new BadRequestException("A base branch is required");
        RepositoryView view = repositories.get(repository).orElseThrow(NotFoundException::new);
        var factory = accounts.resolve(repository, ProviderRole.FACTORY);
        var account = factory.or(() -> accounts.resolve(repository, ProviderRole.REVIEWER))
                .orElseThrow(() -> refused(409, "repository_account_missing"));
        ProviderRole role = factory.isPresent() ? ProviderRole.FACTORY : ProviderRole.REVIEWER;
        String name = branch.trim();
        try {
            return new Head(name, clients.diffSource(account).fetchBranchHead(new RepoRef(view.workspace(), view.slug()), name), role.name());
        } catch (UnsupportedOperationException unsupported) {
            throw refused(501, "branch_head_unsupported");
        } catch (RuntimeException refusedByForge) {
            // Only the failure type is logged. Adapter messages carry request paths and response-body
            // snippets from the forge, and the branch is operator input, so neither goes to the log.
            LOG.warnf("repository %s: the forge did not confirm the requested branch (%s)",
                    repository, refusedByForge.getClass().getSimpleName());
            throw refused(502, "branch_head_unconfirmed");
        }
    }

    /** The same {reason} body every other factory refusal uses, so the screen can say what to change. */
    private static WebApplicationException refused(int status, String reason) {
        return new WebApplicationException(Response.status(status).type(MediaType.APPLICATION_JSON)
                .entity(Map.of("reason", reason)).build());
    }
}
