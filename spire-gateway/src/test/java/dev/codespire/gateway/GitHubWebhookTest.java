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
 * GitHub gateway edge: a signed webhook routed by {key} resolves the registered
 * repo's secret from the DB, verifies, and lands a keyed, typed event on
 * cs.integration; bad signatures, unknown keys, wrong-repo payloads and ping
 * events never reach the bus.
 */
@QuarkusTest
@QuarkusTestResource(KafkaCompanionResource.class)
class GitHubWebhookTest {

    private static final String SECRET = "gh-e2e-webhook-secret";
    private static final String KEY = "test-routing-key-abc";
    private static final String ORG_KEY = "test-org-key-xyz";
    private static final String OWNER = "artyomsv";
    private static final String REPO = "artyomsv/spire-test";

    @InjectKafkaCompanion
    KafkaCompanion companion;

    @Inject
    DataSource dataSource;

    @Inject
    EncryptionService encryption; // the gateway's webhook keyset

    @BeforeEach
    void seedWebhookRepo() throws Exception {
        UUID repoId = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID orgId = UUID.fromString("22222222-2222-2222-2222-222222222222");
        String repoSecret = encryption.encryptString(SECRET, "webhook:" + repoId);
        String orgSecret = encryption.encryptString(SECRET, "webhook:" + orgId);
        // gateway.webhook_repo is created by the gateway's own Flyway migration.
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM webhook_repo");
            st.execute("INSERT INTO webhook_repo (id, provider_type, scope, target, webhook_key, "
                    + "webhook_secret, enabled) VALUES ('" + repoId + "', 'github', 'repo', '" + REPO + "', '"
                    + KEY + "', '" + repoSecret + "', TRUE)");
            st.execute("INSERT INTO webhook_repo (id, provider_type, scope, target, webhook_key, "
                    + "webhook_secret, enabled) VALUES ('" + orgId + "', 'github', 'org', '" + OWNER + "', '"
                    + ORG_KEY + "', '" + orgSecret + "', TRUE)");
        }
    }

    private static final String PR_OPENED = """
            {
              "action": "opened",
              "repository": { "full_name": "artyomsv/spire-test" },
              "pull_request": {
                "number": 7,
                "title": "E2E: add feature",
                "body": "e2e test PR",
                "merged": false,
                "head": { "ref": "feature/e2e", "sha": "e2ecafe0000111" },
                "base": { "ref": "main" },
                "user": { "id": 4242, "login": "octocat" },
                "html_url": "https://github.com/artyomsv/spire-test/pull/7"
              }
            }
            """;

    @Test
    void signedWebhookLandsKeyedAndTypedOnIntegrationTopic() throws Exception {
        byte[] body = PR_OPENED.getBytes(StandardCharsets.UTF_8);
        RestAssured.given()
                .header("X-GitHub-Event", "pull_request")
                .header("X-Hub-Signature-256", "sha256=" + hmac(body))
                .body(body)
                .post("/webhooks/github/" + KEY)
                .then().statusCode(202);

        ConsumerTask<String, String> task = companion.consumeStrings().fromTopics("cs.integration", 1);
        task.awaitCompletion(Duration.ofSeconds(15));
        ConsumerRecord<String, String> record = task.getFirstRecord();

        assertEquals("review::artyomsv/spire-test#7", record.key());
        assertTrue(record.value().contains("\"type\":\"PullRequestEventReceived\""));
        assertTrue(record.value().contains("\"prId\":7"));
        assertTrue(record.value().contains("e2ecafe0000111"));
    }

    @Test
    void invalidSignatureIsRejectedWithoutPublishing() {
        byte[] body = PR_OPENED.getBytes(StandardCharsets.UTF_8);
        RestAssured.given()
                .header("X-GitHub-Event", "pull_request")
                .header("X-Hub-Signature-256", "sha256=" + "00".repeat(32))
                .body(body)
                .post("/webhooks/github/" + KEY)
                .then().statusCode(401);
    }

    @Test
    void unknownKeyIsNotFound() {
        byte[] body = PR_OPENED.getBytes(StandardCharsets.UTF_8);
        RestAssured.given()
                .header("X-GitHub-Event", "pull_request")
                .header("X-Hub-Signature-256", "sha256=deadbeef")
                .body(body)
                .post("/webhooks/github/no-such-key")
                .then().statusCode(404);
    }

    @Test
    void payloadForADifferentRepoIsRejected() throws Exception {
        byte[] body = PR_OPENED.replace("artyomsv/spire-test", "someone/other-repo")
                .getBytes(StandardCharsets.UTF_8);
        RestAssured.given()
                .header("X-GitHub-Event", "pull_request")
                .header("X-Hub-Signature-256", "sha256=" + hmac(body))
                .body(body)
                .post("/webhooks/github/" + KEY)
                .then().statusCode(400);
    }

    @Test
    void orgScopeAcceptsAnyRepoUnderTheOwner() throws Exception {
        // A different repo under the SAME owner — the org-scoped registration accepts it.
        byte[] body = PR_OPENED.replace("artyomsv/spire-test", "artyomsv/another-repo")
                .getBytes(StandardCharsets.UTF_8);
        RestAssured.given()
                .header("X-GitHub-Event", "pull_request")
                .header("X-Hub-Signature-256", "sha256=" + hmac(body))
                .body(body)
                .post("/webhooks/github/" + ORG_KEY)
                .then().statusCode(202);
    }

    @Test
    void orgScopeRejectsARepoUnderADifferentOwner() throws Exception {
        byte[] body = PR_OPENED.replace("artyomsv/spire-test", "someoneelse/repo")
                .getBytes(StandardCharsets.UTF_8);
        RestAssured.given()
                .header("X-GitHub-Event", "pull_request")
                .header("X-Hub-Signature-256", "sha256=" + hmac(body))
                .body(body)
                .post("/webhooks/github/" + ORG_KEY)
                .then().statusCode(400);
    }

    @Test
    void pingEventIsAcceptedButPublishesNothing() throws Exception {
        byte[] body = "{\"zen\":\"Keep it simple\"}".getBytes(StandardCharsets.UTF_8);
        RestAssured.given()
                .header("X-GitHub-Event", "ping")
                .header("X-Hub-Signature-256", "sha256=" + hmac(body))
                .body(body)
                .post("/webhooks/github/" + KEY)
                .then().statusCode(204);
    }

    private static String hmac(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }
}
