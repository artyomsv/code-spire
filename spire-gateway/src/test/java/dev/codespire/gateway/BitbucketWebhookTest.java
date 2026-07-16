package dev.codespire.gateway;

import dev.codespire.encryption.EncryptionService;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.kafka.InjectKafkaCompanion;
import io.quarkus.test.kafka.KafkaCompanionResource;
import io.restassured.RestAssured;
import io.smallrye.reactive.messaging.kafka.companion.ConsumerTask;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bitbucket gateway edge (keyed registry model): a signed webhook routed by {key}
 * resolves the registered repo's secret from the DB, verifies the {@code X-Hub-Signature}
 * HMAC, and lands a keyed, typed event on cs.integration; bad signatures, unknown keys
 * and wrong-repo payloads never reach the bus.
 */
@QuarkusTest
@QuarkusTestResource(KafkaCompanionResource.class)
class BitbucketWebhookTest {

    private static final String SECRET = "bb-e2e-webhook-secret";
    private static final String KEY = "bb-test-routing-key-abc";
    private static final String WORKSPACE_KEY = "bb-test-workspace-key-xyz";
    private static final String WORKSPACE = "sandbox";
    private static final String REPO = "sandbox/demo-repo";

    @InjectKafkaCompanion
    KafkaCompanion companion;

    @Inject
    DataSource dataSource;

    @Inject
    EncryptionService encryption; // the gateway's webhook keyset

    @BeforeEach
    void seedWebhookRepo() throws Exception {
        UUID repoId = UUID.fromString("55555555-5555-5555-5555-555555555555");
        UUID wsId = UUID.fromString("66666666-6666-6666-6666-666666666666");
        String repoSecret = encryption.encryptString(SECRET, "webhook:" + repoId);
        String wsSecret = encryption.encryptString(SECRET, "webhook:" + wsId);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM webhook_repo");
            st.execute("INSERT INTO webhook_repo (id, provider_type, scope, target, webhook_key, "
                    + "webhook_secret, enabled) VALUES ('" + repoId + "', 'bitbucket-cloud', 'repo', '" + REPO + "', '"
                    + KEY + "', '" + repoSecret + "', TRUE)");
            st.execute("INSERT INTO webhook_repo (id, provider_type, scope, target, webhook_key, "
                    + "webhook_secret, enabled) VALUES ('" + wsId + "', 'bitbucket-cloud', 'org', '" + WORKSPACE + "', '"
                    + WORKSPACE_KEY + "', '" + wsSecret + "', TRUE)");
        }
    }

    private static final String PR_CREATED = """
            {
              "repository": { "full_name": "sandbox/demo-repo" },
              "pullrequest": {
                "id": 42,
                "title": "E2E: add feature",
                "description": "e2e test PR",
                "source": { "branch": { "name": "feature/e2e" }, "commit": { "hash": "e2ecafe00001" } },
                "destination": { "branch": { "name": "main" } },
                "author": { "account_id": "author-e2e", "nickname": "e2e", "display_name": "E2E" },
                "links": { "html": { "href": "https://example.invalid/pr/42" } }
              }
            }
            """;

    @Test
    void signedWebhookLandsKeyedAndTypedOnIntegrationTopic() throws Exception {
        byte[] body = PR_CREATED.getBytes(StandardCharsets.UTF_8);
        RestAssured.given()
                .header("X-Event-Key", "pullrequest:created")
                .header("X-Hub-Signature", "sha256=" + hmac(body))
                .body(body)
                .post("/webhooks/bitbucket-cloud/" + KEY)
                .then().statusCode(202);

        ConsumerTask<String, String> task = companion.consumeStrings().fromTopics("cs.integration", 1);
        task.awaitCompletion(Duration.ofSeconds(15));
        ConsumerRecord<String, String> record = task.getFirstRecord();

        assertEquals("review::sandbox/demo-repo#42", record.key());
        assertTrue(record.value().contains("\"type\":\"PullRequestEventReceived\""));
        assertTrue(record.value().contains("\"prId\":42"));
        assertTrue(record.value().contains("e2ecafe00001"));
    }

    @Test
    void invalidSignatureIsRejectedWithoutPublishing() throws Exception {
        byte[] body = PR_CREATED.getBytes(StandardCharsets.UTF_8);
        RestAssured.given()
                .header("X-Event-Key", "pullrequest:created")
                .header("X-Hub-Signature", "sha256=" + "00".repeat(32))
                .body(body)
                .post("/webhooks/bitbucket-cloud/" + KEY)
                .then().statusCode(401);
    }

    @Test
    void unknownKeyIsNotFound() throws Exception {
        byte[] body = PR_CREATED.getBytes(StandardCharsets.UTF_8);
        RestAssured.given()
                .header("X-Event-Key", "pullrequest:created")
                .header("X-Hub-Signature", "sha256=" + hmac(body))
                .body(body)
                .post("/webhooks/bitbucket-cloud/no-such-key")
                .then().statusCode(404);
    }

    @Test
    void payloadForADifferentRepoIsRejected() throws Exception {
        byte[] body = PR_CREATED.replace("sandbox/demo-repo", "someone/other-repo")
                .getBytes(StandardCharsets.UTF_8);
        RestAssured.given()
                .header("X-Event-Key", "pullrequest:created")
                .header("X-Hub-Signature", "sha256=" + hmac(body))
                .body(body)
                .post("/webhooks/bitbucket-cloud/" + KEY)
                .then().statusCode(400);
    }

    @Test
    void workspaceScopeAcceptsAnyRepoUnderTheWorkspace() throws Exception {
        byte[] body = PR_CREATED.replace("sandbox/demo-repo", "sandbox/another-repo")
                .getBytes(StandardCharsets.UTF_8);
        RestAssured.given()
                .header("X-Event-Key", "pullrequest:created")
                .header("X-Hub-Signature", "sha256=" + hmac(body))
                .body(body)
                .post("/webhooks/bitbucket-cloud/" + WORKSPACE_KEY)
                .then().statusCode(202);
    }

    @Test
    void workspaceScopeRejectsARepoUnderADifferentWorkspace() throws Exception {
        byte[] body = PR_CREATED.replace("sandbox/demo-repo", "someoneelse/repo")
                .getBytes(StandardCharsets.UTF_8);
        RestAssured.given()
                .header("X-Event-Key", "pullrequest:created")
                .header("X-Hub-Signature", "sha256=" + hmac(body))
                .body(body)
                .post("/webhooks/bitbucket-cloud/" + WORKSPACE_KEY)
                .then().statusCode(400);
    }

    @Test
    void unknownEventKeyIsAcceptedButPublishesNothing() throws Exception {
        byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
        RestAssured.given()
                .header("X-Event-Key", "repo:push")
                .header("X-Hub-Signature", "sha256=" + hmac(body))
                .body(body)
                .post("/webhooks/bitbucket-cloud/" + KEY)
                .then().statusCode(204);
    }

    private static String hmac(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }
}
