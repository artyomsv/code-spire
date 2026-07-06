package dev.codespire.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ScmCredential;
import dev.codespire.encryption.EncryptionService;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kafka.InjectKafkaCompanion;
import io.quarkus.test.kafka.KafkaCompanionResource;
import io.smallrye.reactive.messaging.kafka.companion.ConsumerTask;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import jakarta.inject.Inject;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.time.Duration;
import java.util.List;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Split test for the worker deployable: JSON commands consumed from
 * cs.commands drive REAL Bitbucket adapters (WireMock) + the stub LLM, and
 * results land typed and keyed on cs.results. Redelivered PostComments must
 * not duplicate comments (comment_idempotency).
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@QuarkusTestResource(KafkaCompanionResource.class)
@QuarkusTestResource(BitbucketWireMockResource.class)
class WorkerPipelineTest {

    private static final RepoRef REPO = new RepoRef("sandbox", "demo-repo");
    private static final String REVIEW_ID = "review::sandbox/demo-repo#42";
    private static final String COMMIT = "e2ecafe00001";
    private static final String COMMENTS = "/repositories/sandbox/demo-repo/pullrequests/42/comments";

    @InjectKafkaCompanion
    KafkaCompanion companion;

    @Inject
    ObjectMapper mapper;

    @Inject
    EncryptionService encryption;

    /**
     * ADR-015: the worker builds its SCM client from the command's KEK-encrypted
     * credential, not from .env. Pack one pointing at the WireMock Bitbucket,
     * bound (AAD) to this PR's workspace — exactly as the orchestrator would.
     */
    private String cred() throws Exception {
        ScmCredential c = new ScmCredential("http://localhost:" + BitbucketWireMockResource.server.port(),
                "basic", "e2e-bot", "e2e-app-password", "bot-account-e2e");
        return encryption.encryptString(mapper.writeValueAsString(c), ScmCredential.aad("sandbox"));
    }

    private void sendCommand(ActionCommand command) throws Exception {
        // writerFor: root-level polymorphism (the type discriminator)
        companion.produceStrings().fromRecords(new ProducerRecord<>("cs.commands", command.reviewId(),
                mapper.writerFor(ActionCommand.class).writeValueAsString(command)));
    }

    private List<String> consumeResults(int total) {
        ConsumerTask<String, String> task = companion.consumeStrings().fromTopics("cs.results", total);
        task.awaitCompletion(Duration.ofSeconds(30));
        return task.getRecords().stream().map(r -> r.value()).toList();
    }

    @Test
    @Order(1)
    void fetchDiffEmitsMetadataOnly() throws Exception {
        sendCommand(new ActionCommand.FetchDiff(REVIEW_ID, REPO, 42, COMMIT, cred()));
        List<String> results = consumeResults(1);
        assertTrue(results.getFirst().contains("\"type\":\"DiffFetched\""));
        assertTrue(results.getFirst().contains("\"changedFiles\":1"));
        // ADR-011: metadata only — never diff content on the bus
        assertTrue(results.stream().noneMatch(v -> v.contains("e2eAddedLine")));
    }

    @Test
    @Order(2)
    void gatherContextEmitsTheFanOutTriple() throws Exception {
        sendCommand(new ActionCommand.GatherContext(REVIEW_ID, REPO, 42, COMMIT, Set.of(), List.of()));
        List<String> results = consumeResults(4); // 1 prior + 3 new
        assertTrue(results.stream().anyMatch(v -> v.contains("\"type\":\"ContextRequested\"")));
        assertTrue(results.stream().anyMatch(v -> v.contains("\"type\":\"ContextContributed\"")));
        assertTrue(results.stream().anyMatch(v -> v.contains("\"type\":\"ContextAssembled\"")));
    }

    @Test
    @Order(3)
    void generateReviewUsesRealDiffAndStubLlm() throws Exception {
        sendCommand(new ActionCommand.GenerateReview(REVIEW_ID, REPO, 42, COMMIT, null, 1, null, cred()));
        List<String> results = consumeResults(5);
        String generated = results.stream()
                .filter(v -> v.contains("\"type\":\"ReviewGenerated\"")).findFirst().orElseThrow();
        assertTrue(generated.contains("STUB summary"));
        assertTrue(generated.contains("src/Demo.java"));
    }

    @Test
    @Order(4)
    void postCommentsIsIdempotentAcrossRedelivery() throws Exception {
        ReviewResult findings = new ReviewResult(
                List.of(new Finding("src/Demo.java", new LineRange(2, 2), Severity.INFO,
                        "STUB finding: split test.", null)),
                "STUB summary", new ModelUsage("stub-model", 0, 0, 0));

        sendCommand(new ActionCommand.PostComments(REVIEW_ID, REPO, 42, COMMIT, findings, cred()));
        List<String> results = consumeResults(6);
        String posted = results.stream()
                .filter(v -> v.contains("\"type\":\"CommentsPosted\"")).findFirst().orElseThrow();
        assertTrue(posted.contains("\"summaryCommentId\":\"991\""));
        BitbucketWireMockResource.server.verify(2, postRequestedFor(urlEqualTo(COMMENTS)));

        // redelivery: same command again -> reconstructed CommentsPosted, NO new posts
        sendCommand(new ActionCommand.PostComments(REVIEW_ID, REPO, 42, COMMIT, findings, cred()));
        List<String> after = consumeResults(7);
        long postedCount = after.stream().filter(v -> v.contains("\"type\":\"CommentsPosted\"")).count();
        assertEquals(2, postedCount);
        BitbucketWireMockResource.server.verify(2, postRequestedFor(urlEqualTo(COMMENTS)));
    }
}
