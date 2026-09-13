package dev.codespire.orchestrator.repository;

import dev.codespire.contract.event.*;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.*;
import dev.codespire.orchestrator.caps.*;
import dev.codespire.orchestrator.factory.*;
import dev.codespire.orchestrator.ingress.ManualRegisterResource;
import dev.codespire.orchestrator.llm.LlmModelPricer;
import dev.codespire.orchestrator.pipeline.*;
import dev.codespire.orchestrator.prompt.PromptSampleRenderer;
import dev.codespire.orchestrator.provider.*;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

/**
 * Invokes each real entry point up to its account-resolution boundary. The observer performs the
 * real SQL lookup and decryption, checks the selected id, then stops before network/spend effects.
 * Existing choreography, manual, rerun, prompt, conversation and factory suites test what follows.
 */
@QuarkusTest
@TestSecurity(user = "TEST-admin", roles = {"spire-admin", "spire-viewer"})
class RepositoryResolverCutoverTest extends RepositoryFixture {
    @Inject ReviewProjection reviews;
    @Inject ReviewProviderResolver reviewProviders;
    @Inject WorkerCredentials credentials;
    @Inject IntegrationSaga saga;
    @Inject ConversationSaga conversation;
    @Inject ReviewRerunService reruns;
    @Inject ManualRegisterResource manual;
    @Inject PromptSampleRenderer prompts;
    @Inject MachineAccounts machines;
    @Inject RunResource runsResource;
    @Inject FactoryRunProjection runs;
    @Inject FactoryPullRequests proposals;
    @Inject ProviderResource serving;
    @Inject RepositoryIngressConsumer ingress;
    @Inject com.fasterxml.jackson.databind.ObjectMapper mapper;
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "kafka.bootstrap.servers") String bootstrap;

    private RepositoryView selected;
    private RepoRef repo;
    private String reviewId;
    private UUID reviewer;
    private UUID factory;

    @org.junit.jupiter.api.AfterEach void clearDeadLetterFixture() throws Exception {
        if (reviewId != null) execute("DELETE FROM dlq_entry WHERE kafka_key=?", reviewId);
    }

    @Test void legacyWireDeliveryIsDeadLetteredWithItsProvenanceProblem() throws Exception {
        seed(true);
        var properties = new Properties();
        properties.put("bootstrap.servers", bootstrap);
        properties.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        properties.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        try (var producer = new org.apache.kafka.clients.producer.KafkaProducer<String, String>(properties)) {
            producer.send(new org.apache.kafka.clients.producer.ProducerRecord<>("cs.integration", reviewId,
                    mapper.writerFor(IntegrationEvent.class).writeValueAsString(opened()))).get();
        }
        long deadline = System.nanoTime() + java.time.Duration.ofSeconds(15).toNanos();
        do {
            try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(
                    "SELECT original_topic,message_type,reason FROM dlq_entry WHERE kafka_key=?")) {
                statement.setString(1, reviewId);
                try (var rows = statement.executeQuery()) {
                    if (rows.next()) {
                        assertEquals("cs.integration", rows.getString(1));
                        assertEquals("PullRequestEventReceived", rows.getString(2));
                        assertTrue(rows.getString(3).contains("verified repository identity"));
                        assertFalse(rows.next());
                        return;
                    }
                }
            }
            Thread.sleep(100);
        } while (System.nanoTime() < deadline);
        fail("Legacy ingress must land in the durable dead-letter queue, not be silently acknowledged");
    }

    private void seed(boolean mapped) {
        workspace = "TEST-cutover-" + UUID.randomUUID();
        repo = new RepoRef(workspace, "TEST-repo");
        // Two forges have the same path, and two repositories on this forge select different accounts.
        UUID decoyReviewer = account("REVIEWER", "https://other.example.test");
        repositories.create(new RepositoryInput("gitlab", "https://other.example.test", workspace,
                repo.slug(), true, decoyReviewer, null));
        reviewer = account("REVIEWER"); factory = account("FACTORY");
        selected = repositories.create(repository(reviewer, factory));
        repositories.create(new RepositoryInput("gitlab", origin, workspace, "TEST-other", true,
                account("REVIEWER"), account("FACTORY")));
        reviewId = ReviewIds.reviewId(repo, 7);
        if (mapped) assertTrue(reviews.claimRepository(reviewId, selected.id(), repo, 7));
        reviews.registerHeader(reviewId, repo, 7, "TEST review", "TEST-author", "TEST-human",
                "TEST-feature", "main", "abc1234", origin + "/" + repo.full() + "/-/merge_requests/7",
                "gitlab", "completed", ReviewProjection.STAGE_DIFF);
    }

    @Test void allDispatchPathsUseTheSelectedRepository() throws Exception {
        seed(true);
        RepositoryAccounts real = new RepositoryAccounts(); real.dataSource = dataSource; real.providers = providers;
        QuarkusMock.installMockForType(new RepositoryAccounts() {
            @Override public Optional<ScmProvider> resolve(UUID id, ProviderRole role) {
                assertEquals(selected.id(), id, "Entry point must keep the selected repository id");
                ScmProvider actual = real.resolve(id, role).orElseThrow();
                assertEquals(role == ProviderRole.REVIEWER ? reviewer : factory, actual.id());
                assertEquals("TEST-secret-" + role, actual.secret(), "The real selected ciphertext must decrypt");
                throw new ResolutionObserved(role);
            }
            @Override public Optional<ProviderView> registration(UUID id, ProviderRole role) {
                assertEquals(selected.id(), id);
                assertEquals((role == ProviderRole.REVIEWER ? reviewer : factory).toString(), real.registration(id, role).orElseThrow().id());
                throw new ResolutionObserved(role);
            }
        }, RepositoryAccounts.class);

        observed(ProviderRole.REVIEWER, () -> reviewProviders.resolveForReview(reviewId));
        observed(ProviderRole.REVIEWER, () -> credentials.packForReview(reviewId));
        observed(ProviderRole.REVIEWER, () -> ingress.accept(delivery()));
        observed(ProviderRole.REVIEWER, () -> saga.onRepository(opened(), selected.id()));
        observed(ProviderRole.REVIEWER, () -> reruns.rerun(workspace, repo.slug(), 7));
        observed(ProviderRole.REVIEWER, () -> manual.register(new ManualRegisterResource.RegisterRequest(
                null, null, null, 7L, null, selected.id())));
        observed(ProviderRole.REVIEWER, () -> prompts.render(PromptKind.REVIEW, "TEST", "{{diff}}", reviewId));
        observed(ProviderRole.REVIEWER, () -> conversation.planFollowUp(reply()));
        observed(ProviderRole.REVIEWER, () -> serving.serving(selected.id().toString()));
        observed(ProviderRole.FACTORY, () -> machines.resolve(selected.id()));
        observed(ProviderRole.FACTORY, () -> runsResource.dispatch(new RunResource.DispatchRequest(null, null, null,
                "main", "0123456789abcdef0123456789abcdef01234567", "TEST fix", "codex", "TEST-model", "TEST-cutover", null, selected.id())));
        FixRunDispatcher fix = fixDispatcher();
        observed(ProviderRole.FACTORY, () -> fix.dispatch(reviewId, repo, "TEST-thread", "TEST-comment", null));
        String runId = "run::gitlab:" + repo.full() + ":TEST-proposal:1";
        assertTrue(runs.queued(new FactoryRunProjection.QueuedRun(runId, "codex", "TEST-model", "main",
                "abc1234", "TEST-feature", "TEST-factory", null), "TEST proposal", selected.id()));
        observed(ProviderRole.FACTORY, () -> proposals.propose(new RunResult.RunFinished(runId,
                "refs/heads/TEST-feature", List.of("TEST.txt"), List.of(), null, false)));
    }

    @Test void unmappedLegacyReviewCannotDispatch() throws Exception {
        seed(false);
        QuarkusMock.installMockForType(new ProviderClients() {
            @Override public dev.codespire.contract.port.DiffSource diffSource(ScmProvider account) {
                throw new AssertionError("An unmapped review must not contact a forge");
            }
        }, ProviderClients.class);
        assertTrue(reviewProviders.resolveForReview(reviewId).isEmpty());
        assertTrue(credentials.packForReview(reviewId).isEmpty());
        assertTrue(conversation.planFollowUp(reply()).isEmpty());
        assertThrows(jakarta.ws.rs.NotFoundException.class, () -> reruns.rerun(workspace, repo.slug(), 7));
        var refused = assertThrows(jakarta.ws.rs.ClientErrorException.class, () -> manual.register(
                new ManualRegisterResource.RegisterRequest(null, null, null, 7L, null, selected.id())));
        assertEquals(409, refused.getResponse().getStatus());
        var emitted = new ArrayList<dev.codespire.contract.command.ActionCommand>();
        QuarkusMock.installMockForType(new CommandsEmitter() {
            @Override public void emit(dev.codespire.contract.command.ActionCommand command) { emitted.add(command); }
        }, CommandsEmitter.class);
        ingress.accept(delivery());
        assertTrue(reviews.repositoryIdOf(reviewId).isEmpty(), "Ingress must not silently claim an existing unmapped review");
        assertTrue(emitted.isEmpty(), "No command may leave for an unmapped review");
        assertInstanceOf(FixRunDispatcher.Refused.class,
                fixDispatcher().dispatch(reviewId, repo, "TEST-thread", "TEST-comment", null));
        // Positive control uses the same stored header, account and entry point after explicit repair.
        execute("UPDATE review_status SET repository_id=? WHERE review_id=?", selected.id(), reviewId);
        assertEquals(reviewer, reviewProviders.resolveForReview(reviewId).orElseThrow().id());
        assertTrue(credentials.packForReview(reviewId).isPresent());
    }

    private IntegrationEvent.PullRequestEventReceived opened() {
        return new IntegrationEvent.PullRequestEventReceived(repo, 7, IntegrationEvent.PrAction.OPENED,
                "TEST review", "", "TEST-feature", "main", "abc1234", Author.of("TEST-human", "TEST-author", "TEST Author"),
                origin + "/" + repo.full() + "/-/merge_requests/7", "gitlab");
    }
    private RepositoryDelivery delivery() {
        return new RepositoryDelivery(selected.id(), null, 0, "gitlab", origin, RepositoryEventKind.REVIEWER,
                "TEST-delivery", opened());
    }
    private IntegrationEvent.AuthorReplied reply() {
        return new IntegrationEvent.AuthorReplied(repo, 7, reviewId, new ThreadRef("TEST-thread"), "TEST-comment",
                "TEST question", Author.of("TEST-human", "TEST-author", "TEST Author"));
    }
    private static void observed(ProviderRole role, org.junit.jupiter.api.function.Executable action) {
        assertEquals(role, assertThrows(ResolutionObserved.class, action).role);
    }
    private static final class ResolutionObserved extends Error {
        final ProviderRole role;
        ResolutionObserved(ProviderRole role) { this.role = role; }
    }

    private FixRunDispatcher fixDispatcher() throws Exception {
        FixRunDispatcher fix = new FixRunDispatcher();
        set(fix, "reviews", reviews); set(fix, "runs", runs); set(fix, "machineAccounts", machines);
        set(fix, "spendGate", new SpendGate() {
            @Override public Decision decide() { return Decision.of(CapRefusal.allow()); }
        });
        set(fix, "plans", new FixDispatch() {
            @Override public Plan plan(String id, String thread, RepoRef target) {
                return new Planned("run::gitlab:" + repo.full() + ":TEST-fix:1", "main", "TEST-feature", "abc1234",
                        "main", ScmType.GITLAB, workspace, repo.slug());
            }
        });
        set(fix, "pricer", new LlmModelPricer() {
            @Override public boolean isPriceable(String model) { return true; }
        });
        set(fix, "config", new FactoryConfig() {
            public Map<String, String> agentImage() { return Map.of("codex", "TEST-image"); }
            public long wallClockSeconds() { return 1; }
            public Fix fix() { return new Fix() {
                public Optional<String> harness() { return Optional.of("codex"); }
                public Optional<String> model() { return Optional.of("TEST-model"); }
            }; }
        });
        return fix;
    }
    private static void set(Object target, String name, Object value) throws Exception {
        var field = target.getClass().getDeclaredField(name); field.setAccessible(true); field.set(target, value);
    }
}
