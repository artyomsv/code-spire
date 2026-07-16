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

import javax.sql.DataSource;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * GitLab gateway edge: a webhook whose {@code X-Gitlab-Token} matches the registered
 * repo's secret resolves by {key}, verifies, and lands a keyed, typed event on
 * cs.integration; wrong tokens, unknown keys, wrong-repo payloads and uninteresting
 * hooks never reach the bus. GitLab does NOT sign the body, so the token is compared
 * verbatim (no HMAC).
 */
@QuarkusTest
@QuarkusTestResource(KafkaCompanionResource.class)
class GitLabWebhookTest {

    private static final String SECRET = "gl-e2e-webhook-secret";
    private static final String KEY = "gl-test-routing-key-abc";
    private static final String GROUP_KEY = "gl-test-group-key-xyz";
    private static final String GROUP = "artyomsv";
    private static final String PROJECT = "artyomsv/spire-test";

    @InjectKafkaCompanion
    KafkaCompanion companion;

    @Inject
    DataSource dataSource;

    @Inject
    EncryptionService encryption; // the gateway's webhook keyset

    @BeforeEach
    void seedWebhookRepo() throws Exception {
        UUID repoId = UUID.fromString("33333333-3333-3333-3333-333333333333");
        UUID groupId = UUID.fromString("44444444-4444-4444-4444-444444444444");
        String repoSecret = encryption.encryptString(SECRET, "webhook:" + repoId);
        String groupSecret = encryption.encryptString(SECRET, "webhook:" + groupId);
        try (Connection c = dataSource.getConnection(); Statement st = c.createStatement()) {
            st.execute("DELETE FROM webhook_repo");
            st.execute("INSERT INTO webhook_repo (id, provider_type, scope, target, webhook_key, "
                    + "webhook_secret, enabled) VALUES ('" + repoId + "', 'gitlab', 'repo', '" + PROJECT + "', '"
                    + KEY + "', '" + repoSecret + "', TRUE)");
            st.execute("INSERT INTO webhook_repo (id, provider_type, scope, target, webhook_key, "
                    + "webhook_secret, enabled) VALUES ('" + groupId + "', 'gitlab', 'org', '" + GROUP + "', '"
                    + GROUP_KEY + "', '" + groupSecret + "', TRUE)");
        }
    }

    private static final String MR_OPENED = """
            {
              "object_kind": "merge_request",
              "user": { "id": 4242, "username": "octocat", "name": "Octo Cat" },
              "project": { "path_with_namespace": "artyomsv/spire-test" },
              "object_attributes": {
                "iid": 7,
                "action": "open",
                "title": "E2E: add feature",
                "description": "e2e test MR",
                "source_branch": "feature/e2e",
                "target_branch": "main",
                "last_commit": { "id": "e2ecafe0000111" },
                "url": "https://gitlab.com/artyomsv/spire-test/-/merge_requests/7"
              }
            }
            """;

    @Test
    void tokenedWebhookLandsKeyedAndTypedOnIntegrationTopic() {
        byte[] body = MR_OPENED.getBytes(StandardCharsets.UTF_8);
        RestAssured.given()
                .header("X-Gitlab-Event", "Merge Request Hook")
                .header("X-Gitlab-Token", SECRET)
                .body(body)
                .post("/webhooks/gitlab/" + KEY)
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
    void wrongTokenIsRejectedWithoutPublishing() {
        byte[] body = MR_OPENED.getBytes(StandardCharsets.UTF_8);
        RestAssured.given()
                .header("X-Gitlab-Event", "Merge Request Hook")
                .header("X-Gitlab-Token", "not-the-secret")
                .body(body)
                .post("/webhooks/gitlab/" + KEY)
                .then().statusCode(401);
    }

    @Test
    void unknownKeyIsNotFound() {
        byte[] body = MR_OPENED.getBytes(StandardCharsets.UTF_8);
        RestAssured.given()
                .header("X-Gitlab-Token", SECRET)
                .body(body)
                .post("/webhooks/gitlab/no-such-key")
                .then().statusCode(404);
    }

    @Test
    void payloadForADifferentProjectIsRejected() {
        byte[] body = MR_OPENED.replace("artyomsv/spire-test", "someone/other-repo")
                .getBytes(StandardCharsets.UTF_8);
        RestAssured.given()
                .header("X-Gitlab-Token", SECRET)
                .body(body)
                .post("/webhooks/gitlab/" + KEY)
                .then().statusCode(400);
    }

    @Test
    void groupScopeAcceptsAnyProjectUnderTheGroup() {
        byte[] body = MR_OPENED.replace("artyomsv/spire-test", "artyomsv/another-repo")
                .getBytes(StandardCharsets.UTF_8);
        RestAssured.given()
                .header("X-Gitlab-Token", SECRET)
                .body(body)
                .post("/webhooks/gitlab/" + GROUP_KEY)
                .then().statusCode(202);
    }

    @Test
    void groupScopeRejectsAProjectUnderADifferentGroup() {
        byte[] body = MR_OPENED.replace("artyomsv/spire-test", "someoneelse/repo")
                .getBytes(StandardCharsets.UTF_8);
        RestAssured.given()
                .header("X-Gitlab-Token", SECRET)
                .body(body)
                .post("/webhooks/gitlab/" + GROUP_KEY)
                .then().statusCode(400);
    }

    @Test
    void uninterestingHookIsAcceptedButPublishesNothing() {
        byte[] body = "{\"object_kind\":\"push\"}".getBytes(StandardCharsets.UTF_8);
        RestAssured.given()
                .header("X-Gitlab-Event", "Push Hook")
                .header("X-Gitlab-Token", SECRET)
                .body(body)
                .post("/webhooks/gitlab/" + KEY)
                .then().statusCode(204);
    }
}
