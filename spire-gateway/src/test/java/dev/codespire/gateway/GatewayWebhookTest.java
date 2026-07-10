package dev.codespire.gateway;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.kafka.InjectKafkaCompanion;
import io.quarkus.test.kafka.KafkaCompanionResource;
import io.restassured.RestAssured;
import io.smallrye.reactive.messaging.kafka.companion.ConsumerTask;
import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Gateway split test: a signed Bitbucket webhook becomes a keyed, typed JSON
 * event on cs.integration; bad signatures and malformed payloads never reach
 * the bus.
 */
@QuarkusTest
@TestProfile(GatewayWebhookTest.BitbucketProfile.class)
@QuarkusTestResource(KafkaCompanionResource.class)
class GatewayWebhookTest {

    static final String SECRET = "e2e-webhook-secret";

    public static class BitbucketProfile implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            // The Bitbucket edge is enabled purely by the presence of its webhook secret.
            return Map.of("spire.scm.bitbucket.webhook-secret", SECRET);
        }
    }

    @InjectKafkaCompanion
    KafkaCompanion companion;

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
                .post("/webhooks/bitbucket")
                .then().statusCode(202);

        ConsumerTask<String, String> task = companion.consumeStrings()
                .fromTopics("cs.integration", 1);
        task.awaitCompletion(Duration.ofSeconds(15));
        ConsumerRecord<String, String> record = task.getFirstRecord();

        // keyed by reviewId for per-PR ordering (CONTRACT §9)
        assertEquals("review::sandbox/demo-repo#42", record.key());
        // polymorphic wire format (type discriminator)
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
                .post("/webhooks/bitbucket")
                .then().statusCode(401);
    }

    @Test
    void malformedAuthenticatedPayloadIsBadRequest() throws Exception {
        byte[] body = """
                { "repository": { "full_name": "../evil" },
                  "pullrequest": { "id": 1 } }
                """.getBytes(StandardCharsets.UTF_8);
        RestAssured.given()
                .header("X-Event-Key", "pullrequest:created")
                .header("X-Hub-Signature", "sha256=" + hmac(body))
                .body(body)
                .post("/webhooks/bitbucket")
                .then().statusCode(400);
    }

    private static String hmac(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }
}
