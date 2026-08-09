package dev.codespire.orchestrator.web;

import dev.codespire.orchestrator.readmodel.ReviewFixtures;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;

/**
 * The REST surface archiving replaced DELETE with. Each refusal must arrive as its own answer: the
 * UPDATE behind it matches zero rows for "no such review", "already archived" and "still running"
 * alike, so an operator who cannot tell them apart cannot act on any of them.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class ReviewArchiveResourceTest {

    @Inject
    ReviewProjection projection;

    private static String path(long pr, String action) {
        return "/api/reviews/" + ReviewFixtures.WS + "/" + ReviewFixtures.REPO + "/" + pr + "/" + action;
    }

    private long seedCompleted() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
        return pr;
    }

    private long seedReviewing() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedReviewingReview(projection, pr);
        return pr;
    }

    @Test
    void archivingAnUnknownReviewIs404() {
        given().when().post(path(ReviewFixtures.newPr(), "archive")).then().statusCode(404);
    }

    @Test
    void archivingTwiceIs409WithAnActionableMessage() {
        long pr = seedCompleted();
        given().when().post(path(pr, "archive")).then().statusCode(204);
        given().when().post(path(pr, "archive")).then().statusCode(409)
                .body(containsString("already archived"));
    }

    @Test
    void archivingARunningReviewIs409AndSaysToWait() {
        long pr = seedReviewing();
        given().when().post(path(pr, "archive")).then().statusCode(409)
                .body(containsString("still running"));
    }

    @Test
    void archivedReviewsAreHiddenByDefaultAndVisibleOnRequest() {
        long pr = seedCompleted();
        given().when().post(path(pr, "archive")).then().statusCode(204);

        when().get("/api/reviews").then().statusCode(200)
                .body("findAll { it.pr == " + pr + " }", hasSize(0));
        when().get("/api/reviews?includeArchived=true").then().statusCode(200)
                .body("findAll { it.pr == " + pr + " }", hasSize(1));
    }

    @Test
    void unarchiveRestoresTheReview() {
        long pr = seedCompleted();
        given().when().post(path(pr, "archive")).then().statusCode(204);
        given().when().post(path(pr, "unarchive")).then().statusCode(204);
        when().get("/api/reviews").then().body("findAll { it.pr == " + pr + " }", hasSize(1));
    }

    @Test
    void unarchivingAReviewThatWasNeverArchivedIs404() {
        long pr = seedCompleted();
        given().when().post(path(pr, "unarchive")).then().statusCode(404);
    }
}
