package dev.codespire.orchestrator.ingress;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.PrAction;
import dev.codespire.contract.event.IntegrationEvent.PullRequestEventReceived;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.DiffRefs;
import dev.codespire.contract.scm.RepoRef;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;

import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Phase 0 stand-in for the real Bitbucket webhook ingress (spire-gateway, P1):
 * emits an OBVIOUSLY-SYNTHETIC PullRequestEventReceived so the pipeline can be
 * exercised end-to-end. All values are self-labeling test data — no real repo,
 * author, or commit is referenced.
 */
@Path("/dev/simulate-pr")
public class DevSimulatorResource {

    private static final RepoRef TEST_REPO = new RepoRef("sandbox", "demo-repo");
    private static final AtomicLong PR_SEQ = new AtomicLong(100);

    @Inject
    @Channel("integration")
    Emitter<IntegrationEvent> integration;

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> simulate() {
        long prId = PR_SEQ.incrementAndGet();
        String commit = "cafe%08x".formatted(ThreadLocalRandom.current().nextInt());

        integration.send(new PullRequestEventReceived(
                TEST_REPO, prId, PrAction.OPENED,
                "TEST: simulated PR #" + prId,
                "TEST: synthetic pull request emitted by the Phase 0 dev simulator.",
                "feature/TEST-demo", "main",
                DiffRefs.headOnly(commit),
                Author.of("TEST-account-id", "test-author", "TEST Author"),
                "https://example.invalid/sandbox/demo-repo/pull-requests/" + prId));

        return Map.of(
                "reviewId", ReviewIds.reviewId(TEST_REPO, prId),
                "prId", prId,
                "commit", commit);
    }
}
