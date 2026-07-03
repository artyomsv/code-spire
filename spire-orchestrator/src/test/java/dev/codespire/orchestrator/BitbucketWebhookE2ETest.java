package dev.codespire.orchestrator;

import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.get;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Phase 1 exit criterion: a REAL Bitbucket webhook (signed) drives the REAL
 * adapter pipeline — diff fetched and parsed, review generated (stub LLM),
 * inline + summary comments POSTed to "Bitbucket" — and a duplicate delivery
 * of the same commit posts NOTHING twice (decider idempotency + the
 * comment_idempotency claim).
 */
@QuarkusTest
@QuarkusTestResource(BitbucketWireMockResource.class)
class BitbucketWebhookE2ETest {

    private static final String COMMENTS = "/repositories/sandbox/demo-repo/pullrequests/42/comments";

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
    void signedWebhookFlowsToPostedReviewExactlyOnce() throws Exception {
        // invalid signature is rejected before anything happens
        RestAssured.given()
                .header("X-Event-Key", "pullrequest:created")
                .header("X-Hub-Signature", "sha256=" + "00".repeat(32))
                .body(PR_CREATED.getBytes(StandardCharsets.UTF_8))
                .post("/webhooks/bitbucket")
                .then().statusCode(401);

        // signed webhook is accepted with 202 and processed asynchronously
        postSignedWebhook();
        awaitTimelineContains("\"ReviewCompleted\"");

        // the REAL adapter posted one inline + one summary comment
        BitbucketWireMockResource.server.verify(2, postRequestedFor(urlEqualTo(COMMENTS)));
        String timeline = get("/api/timeline").asString();
        assertTrue(timeline.contains("\"DiffFetched\""));
        assertTrue(timeline.contains("\"ReviewGenerated\""));
        assertTrue(timeline.contains("\"CommentsPosted\""));

        // duplicate delivery of the SAME commit: accepted, but the decider
        // no-ops (reviewed commit) and no further comments are posted.
        postSignedWebhook();
        Thread.sleep(1_000);
        BitbucketWireMockResource.server.verify(2, postRequestedFor(urlEqualTo(COMMENTS)));
    }

    private void postSignedWebhook() throws Exception {
        byte[] body = PR_CREATED.getBytes(StandardCharsets.UTF_8);
        RestAssured.given()
                .header("X-Event-Key", "pullrequest:created")
                .header("X-Hub-Signature", "sha256=" + hmac(body))
                .body(body)
                .post("/webhooks/bitbucket")
                .then().statusCode(202);
    }

    private void awaitTimelineContains(String marker) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        String timeline = "";
        while (System.currentTimeMillis() < deadline) {
            timeline = get("/api/timeline").asString();
            if (timeline.contains(marker)) {
                return;
            }
            Thread.sleep(250);
        }
        throw new AssertionError("Timeline never showed " + marker + "; got: " + timeline);
    }

    private static String hmac(byte[] body) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(BitbucketWireMockResource.WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8),
                "HmacSHA256"));
        return HexFormat.of().formatHex(mac.doFinal(body));
    }
}
