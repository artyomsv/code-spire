package dev.codespire.orchestrator.ingress;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.PrAction;
import dev.codespire.contract.event.IntegrationEvent.PullRequestEventReceived;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.RepoRef;
import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Dev/test stand-in for the real webhook ingress: emits an OBVIOUSLY-SYNTHETIC
 * PullRequestEventReceived so the pipeline can be exercised end-to-end. All
 * values are self-labeling test data — no real repo, author, or commit.
 * EXCLUDED from prod builds (rules-compliance note: synthetic events must not
 * be injectable into a production pipeline).
 */
@io.quarkus.arc.profile.UnlessBuildProfile("prod")
@Path("/dev/simulate-pr")
@RolesAllowed("spire-admin")
public class DevSimulatorResource {

    private static final AtomicLong PR_SEQ = new AtomicLong(100);

    @Inject
    dev.codespire.orchestrator.repository.RepositoryDeliveryEmitter integration;

    @Inject dev.codespire.orchestrator.repository.RepositoryRegistry repositories;

    /** Runtime belt-and-suspenders behind the build-time prod exclusion (security finding). */
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "spire.scm.stub", defaultValue = "false")
    boolean stubScm;

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> simulate(@jakarta.ws.rs.QueryParam("repositoryId") java.util.UUID repositoryId) {
        if (!stubScm) {
            // never inject synthetic events into a pipeline wired to a real SCM
            throw new jakarta.ws.rs.NotFoundException();
        }
        if (repositoryId == null) throw new jakarta.ws.rs.BadRequestException("Select a registered TEST repository");
        var repository = repositories.get(repositoryId).orElseThrow(jakarta.ws.rs.NotFoundException::new);
        if (!repository.workspace().startsWith("TEST-")) {
            throw new jakarta.ws.rs.BadRequestException("Simulation requires a TEST- namespace");
        }
        long prId = PR_SEQ.incrementAndGet();
        RepoRef testRepo = ReviewIds.parse("review::" + repository.workspace() + "/" + repository.slug() + "#" + prId).repo();
        String commit = "cafe%08x".formatted(ThreadLocalRandom.current().nextInt());

        IntegrationEvent event = new PullRequestEventReceived(
                testRepo, prId, PrAction.OPENED,
                "TEST: simulated PR #" + prId,
                "TEST: synthetic pull request emitted by the dev simulator.",
                "feature/TEST-demo", "main",
                (commit),
                Author.of("TEST-account-id", "test-author", "TEST Author"),
                "https://example.invalid/sandbox/demo-repo/pull-requests/" + prId,
                repository.scmType());
        integration.send(new dev.codespire.contract.event.RepositoryDelivery(repositoryId, null, 0,
                repository.scmType(), repository.forgeOrigin(), dev.codespire.contract.event.RepositoryEventKind.REVIEWER,
                "TEST-simulator-" + prId, event));

        return Map.of(
                "reviewId", ReviewIds.reviewId(testRepo, prId),
                "prId", prId,
                "commit", commit);
    }
}
