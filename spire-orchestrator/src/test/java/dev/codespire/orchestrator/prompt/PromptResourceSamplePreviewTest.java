package dev.codespire.orchestrator.prompt;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.provider.ProviderInput;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.Response;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.emptyOrNullString;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;

/**
 * The {@code /preview} endpoint accepts an optional {@code reviewId} (Task 15): with none it stays
 * the annotated preview {@link PromptResourceTest} already covers; with one it renders through
 * {@link PromptSampleRenderer} against a REAL review, falling back to the annotated preview (with a
 * reason) when the sample cannot be assembled. Admin-only matters more here than on the rest of the
 * resource — a reviewId makes this endpoint render a real pull request's source code into its
 * response.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
// PER_CLASS: one WireMock "SCM" + one registered provider shared by every test in this class, each
// test using its own PR number so the fixtures never collide (same layout as PromptSampleRendererTest).
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PromptResourceSamplePreviewTest {

    private static final RepoRef REPO = new RepoRef("prsp-ws", "repo");

    @Inject
    ProviderRegistry providers;

    @Inject
    ReviewProjection projection;

    private static WireMockServer scm;
    private boolean providerRegistered;
    private final AtomicLong prCounter = new AtomicLong(1);

    @BeforeAll
    static void startScm() {
        scm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        scm.start();
    }

    @AfterAll
    static void stopScm() {
        scm.stop();
    }

    // Registration is deferred out of @BeforeAll (RestAssured/CDI aren't wired up there) and done
    // once: scm_provider has a unique (type, workspace) constraint, and every test shares it.
    @BeforeEach
    void registerProviderOnce() {
        if (providerRegistered) {
            return;
        }
        providers.create(new ProviderInput("PRSP", "bitbucket-cloud", scm.baseUrl(), REPO.workspace(),
                "bearer", null, "provider-tok", "acct", true, List.of(), null, null));
        providerRegistered = true;
    }

    @Test
    void previewWithoutAReviewIdStaysAnnotated() {
        preview("review", "You are a reviewer.", "Diff:\n{{diff}}", null)
                .then().statusCode(200)
                .body("user", containsString("«diff inserted here»"))
                .body("sampleReviewId", nullValue());
    }

    @Test
    void previewWithAReviewIdRendersThatReview() {
        String reviewId = registerReviewWithDiff("src/Foo.java", "CANARY line");

        preview("review", "You are a reviewer.", "Diff:\n{{diff}}", reviewId)
                .then().statusCode(200)
                .body("user", containsString("src/Foo.java"))
                .body("sampleReviewId", is(reviewId))
                .body("unavailableReason", nullValue());
    }

    @Test
    void anUnfetchableDiffFallsBackToAnnotatedAndSaysWhy() {
        // An empty panel would read as a broken preview. The reason is what makes it actionable.
        String reviewId = registerReviewWhoseDiffFetchFails();

        preview("review", "You are a reviewer.", "Diff:\n{{diff}}", reviewId)
                .then().statusCode(200)
                .body("user", containsString("«diff inserted here»"))
                .body("unavailableReason", not(emptyOrNullString()));
    }

    @Test
    @TestSecurity(user = "test-viewer", roles = {"spire-viewer"})
    void aViewerCannotPreview() {
        // Class-level @RolesAllowed("spire-admin") already covers this, and it must keep covering it:
        // the preview now renders a real pull request's source code into its response.
        preview("review", "You are a reviewer.", "Diff:\n{{diff}}", null)
                .then().statusCode(403);
    }

    private Response preview(String kind, String system, String body, String reviewId) {
        return given().contentType(ContentType.JSON)
                .body(requestBody(system, body, reviewId))
                .when().post("/api/prompts/" + kind + "/preview");
    }

    private static String requestBody(String system, String body, String reviewId) {
        String reviewIdJson = reviewId == null ? "null" : "\"" + reviewId + "\"";
        return "{\"system\":\"" + jsonEscape(system) + "\",\"body\":\"" + jsonEscape(body)
                + "\",\"reviewId\":" + reviewIdJson + "}";
    }

    private static String jsonEscape(String s) {
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    /** Each added line of {@code content} becomes one hunk line. */
    private static String unifiedDiffFor(String path, String content) {
        return "diff --git a/" + path + " b/" + path + '\n'
                + "new file mode 100644\n"
                + "index 0000000..1111111\n"
                + "--- /dev/null\n"
                + "+++ b/" + path + '\n'
                + "@@ -0,0 +1,1 @@\n"
                + "+" + content + '\n';
    }

    private String registerReviewWithDiff(String path, String content) {
        long pr = prCounter.getAndIncrement();
        stubDiff(pr, aResponse().withHeader("Content-Type", "text/plain").withBody(unifiedDiffFor(path, content)));
        return registerReview(pr);
    }

    private String registerReviewWhoseDiffFetchFails() {
        long pr = prCounter.getAndIncrement();
        stubDiff(pr, aResponse().withStatus(503));
        return registerReview(pr);
    }

    private void stubDiff(long pr, ResponseDefinitionBuilder response) {
        scm.stubFor(get(urlEqualTo("/repositories/" + REPO.full() + "/pullrequests/" + pr + "/diff"))
                .willReturn(response));
    }

    private String registerReview(long pr) {
        String reviewId = ReviewIds.reviewId(REPO, pr);
        projection.registerHeader(reviewId, REPO, pr, "Sample PR " + pr, "alice", "a1",
                "feature", "main", "commit" + pr, "https://scm.example/" + REPO.full() + "/pull/" + pr,
                "bitbucket-cloud", "reviewing", ReviewProjection.STAGE_DIFF);
        return reviewId;
    }
}
