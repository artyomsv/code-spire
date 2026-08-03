package dev.codespire.worker.web;

import io.quarkus.test.security.TestSecurity;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.port.BlobStore;
import dev.codespire.contract.review.AssembledContext;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.worker.adapters.PostgresBlobStore;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;

import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;

/**
 * The review detail page's Context section reads what the model was actually given, so this serves
 * the stored blob rather than re-resolving references against a live host.
 */
@QuarkusTest
@TestSecurity(user = "test-viewer", roles = "spire-viewer")
class ReviewContextResourceTest {

    @Inject
    PostgresBlobStore store;

    @Inject
    ObjectMapper mapper;

    private void store(String reviewId, AssembledContext context) throws Exception {
        store.put(BlobStore.Kind.CONTEXT, reviewId, mapper.writeValueAsBytes(context));
    }

    @Test
    void returnsTheItemsTheReviewWasGiven() throws Exception {
        String reviewId = "review::acme/widgets#801";
        store(reviewId, new AssembledContext(null,
                List.of(new ContextItem("ISSUE", "acme/widgets#7 Cap discounts at 50%",
                        "State: open\n\nMust reject above 50.", "https://example.invalid/issues/7")),
                Set.of("github-issues"), Set.of()));

        when().get("/wk/review-context/acme/widgets/801")
                .then().statusCode(200)
                .body("items", hasSize(1))
                .body("items[0].kind", is("ISSUE"))
                .body("items[0].title", is("acme/widgets#7 Cap discounts at 50%"))
                .body("contributingSources", hasItem("github-issues"));

        store.deleteByReview(reviewId);
    }

    /** No blob is the normal path when nothing was referenced — an empty result, never an error. */
    @Test
    void returnsAnEmptyResultWhenTheReviewHasNoContext() {
        when().get("/wk/review-context/acme/widgets/802")
                .then().statusCode(200)
                .body("items", hasSize(0))
                .body("contributingSources", hasSize(0));
    }

    @Test
    void reportsSourcesThatWereExpectedButContributedNothing() throws Exception {
        String reviewId = "review::acme/widgets#803";
        store(reviewId, new AssembledContext(null, List.of(),
                Set.of(), Set.of("jira")));

        when().get("/wk/review-context/acme/widgets/803")
                .then().statusCode(200)
                .body("missingSources", hasItem("jira"));

        store.deleteByReview(reviewId);
    }

    /** A blob that fails to parse is a display problem, not a reason to fail the page. */
    @Test
    void returnsAnEmptyResultWhenTheStoredBlobIsNotValidJson() {
        String reviewId = "review::acme/widgets#804";
        store.put(BlobStore.Kind.CONTEXT, reviewId, "not json".getBytes(StandardCharsets.UTF_8));

        when().get("/wk/review-context/acme/widgets/804")
                .then().statusCode(200)
                .body("items", hasSize(0));

        store.deleteByReview(reviewId);
    }
}
