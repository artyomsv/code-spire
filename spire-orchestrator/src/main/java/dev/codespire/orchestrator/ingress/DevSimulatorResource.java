package dev.codespire.orchestrator.ingress;

import dev.codespire.contract.event.EventKeys;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.PrAction;
import dev.codespire.contract.event.IntegrationEvent.PullRequestEventReceived;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.DiffRefs;
import dev.codespire.contract.scm.RepoRef;
import io.smallrye.reactive.messaging.kafka.api.OutgoingKafkaRecordMetadata;
import jakarta.inject.Inject;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.eclipse.microprofile.reactive.messaging.Metadata;

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
public class DevSimulatorResource {

    private static final RepoRef TEST_REPO = new RepoRef("sandbox", "demo-repo");
    private static final AtomicLong PR_SEQ = new AtomicLong(100);

    @Inject
    @Channel("integration-out")
    Emitter<IntegrationEvent> integration;

    /** Runtime belt-and-suspenders behind the build-time prod exclusion (security finding). */
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "spire.scm.provider", defaultValue = "stub")
    String scmProvider;

    @POST
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> simulate() {
        if (!"stub".equals(scmProvider)) {
            // never inject synthetic events into a pipeline wired to a real SCM
            throw new jakarta.ws.rs.NotFoundException();
        }
        long prId = PR_SEQ.incrementAndGet();
        String commit = "cafe%08x".formatted(ThreadLocalRandom.current().nextInt());

        IntegrationEvent event = new PullRequestEventReceived(
                TEST_REPO, prId, PrAction.OPENED,
                "TEST: simulated PR #" + prId,
                "TEST: synthetic pull request emitted by the dev simulator.",
                "feature/TEST-demo", "main",
                DiffRefs.headOnly(commit),
                Author.of("TEST-account-id", "test-author", "TEST Author"),
                "https://example.invalid/sandbox/demo-repo/pull-requests/" + prId);
        integration.send(Message.of(event, Metadata.of(
                OutgoingKafkaRecordMetadata.<String>builder().withKey(EventKeys.of(event)).build())));

        return Map.of(
                "reviewId", ReviewIds.reviewId(TEST_REPO, prId),
                "prId", prId,
                "commit", commit);
    }
}
