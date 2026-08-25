package dev.codespire.orchestrator.prompt;

import io.quarkus.test.security.TestSecurity;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptValidation;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.provider.ProviderInput;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
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
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sample preview renders a candidate template against a REAL review's diff — re-fetched by
 * commit (ADR-011), never a bundled sample (the no-fabricated-data rule this task exists to honor).
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
// PER_CLASS: one WireMock "SCM" + one registered provider shared by every test in this class, each
// test using its own PR number so the fixtures never collide.
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PromptSampleRendererTest {

    private static final RepoRef REPO = new RepoRef("psr-ws", "repo");
    private static final int REVIEW_DIFF_MAX_TOKENS = 24_000;

    @Inject
    PromptSampleRenderer renderer;

    @Inject
    ProviderRegistry providers;

    @Inject
    ReviewProjection projection;

    private static WireMockServer scm;
    private static boolean providerRegistered;
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
        providers.create(new ProviderInput("PSR", "bitbucket-cloud", scm.baseUrl(), REPO.workspace(),
                "bearer", null, "provider-tok", "acct", true, List.of(), null, null));
        providerRegistered = true;
    }

    @Test
    void rendersTheCandidateTemplateAgainstTheReviewsRealDiff() {
        String reviewId = registerReviewWithDiff("src/Foo.java", "TESTUSDT placeholder line");

        PromptValidation.PromptPreview preview = renderer.render(PromptKind.REVIEW,
                "You are a reviewer.", "Diff:\n{{diff}}", reviewId);

        assertTrue(preview.user().contains("src/Foo.java"));
        assertFalse(preview.user().contains("«diff inserted here»"));
    }

    @Test
    void showsTheUntrustedDataFenceTheRendererApplies() {
        // The annotated preview shows no fence at all, so an operator cannot see the injection
        // boundary their template's variables sit inside.
        String reviewId = registerReviewWithDiff("src/Foo.java", "TESTUSDT placeholder line");

        PromptValidation.PromptPreview preview = renderer.render(PromptKind.REVIEW,
                "You are a reviewer.", "Diff:\n{{diff}}", reviewId);

        assertTrue(preview.user().contains("BEGIN_UNTRUSTED_DATA"));
        assertTrue(preview.user().contains("END_UNTRUSTED_DATA"));
    }

    @Test
    void clipsExactlyAsARealReviewWould() {
        // A large diff is clipped before it reaches the model. An operator cannot currently see
        // that happening, which is half the reason this preview exists.
        String synthetic = oversizedButObviouslySyntheticDiff();
        String reviewId = registerReviewWithDiff("src/Big.java", synthetic);

        PromptValidation.PromptPreview preview = renderer.render(PromptKind.REVIEW,
                "You are a reviewer.", "Diff:\n{{diff}}", reviewId);

        // TokenBudget's real clip marker is "...(truncated to fit the model context)" (three ASCII
        // dots, not a single U+2026 ellipsis) -- asserting the actual production text rather than a
        // glyph that never appears is what makes this assertion meaningful.
        assertTrue(preview.user().contains("(truncated to fit the model context)"));
        assertTrue(preview.user().length() < synthetic.length());
    }

    @Test
    void anUnfetchableDiffFailsWithAReasonRatherThanAnEmptyPanel() {
        String reviewId = registerReviewWhoseDiffFetchFails();

        PromptSampleRenderer.PromptSampleUnavailable ex = assertThrows(
                PromptSampleRenderer.PromptSampleUnavailable.class,
                () -> renderer.render(PromptKind.REVIEW, "You are a reviewer.", "Diff:\n{{diff}}", reviewId));
        assertTrue(ex.getMessage().contains("diff"));
    }

    @Test
    void aReviewThatDoesNotExistIsNotFound() {
        String reviewId = ReviewIds.reviewId(REPO, 9_999_999L);
        assertThrows(NotFoundException.class,
                () -> renderer.render(PromptKind.REVIEW, "You are a reviewer.", "Diff:\n{{diff}}", reviewId));
    }

    @Test
    void aReviewWithNoRegisteredProviderIsUnavailable() {
        // Deliberately NOT named to contain the substring "provider" -- a workspace slug that does
        // would make the message assertion below pass regardless of what the guard actually says.
        RepoRef unregistered = new RepoRef("psr-unclaimed", "repo");
        String reviewId = ReviewIds.reviewId(unregistered, 1);
        projection.registerHeader(reviewId, unregistered, 1, "Sample", "alice", "a1",
                "feature", "main", "abc", "https://scm.example/x", "bitbucket-cloud", "reviewing",
                ReviewProjection.STAGE_DIFF);

        PromptSampleRenderer.PromptSampleUnavailable ex = assertThrows(
                PromptSampleRenderer.PromptSampleUnavailable.class,
                () -> renderer.render(PromptKind.REVIEW, "You are a reviewer.", "Diff:\n{{diff}}", reviewId));
        assertTrue(ex.getMessage().contains("provider"));
    }

    /**
     * A connection failure (unreachable host) is an I/O bug, not a classified SCM API error --
     * {@code BitbucketCloudClient} reports it as {@code UncheckedIOException}, which does NOT
     * implement {@code ScmApiException}. The renderer must let it propagate rather than reporting a
     * genuine infrastructure fault as the same "unavailable" outcome an honest 404/503 produces.
     */
    @Test
    void aGenuineBugPropagatesRatherThanBeingReportedAsUnavailable() {
        providers.create(new ProviderInput("PSR-DOWN", "bitbucket-cloud", "http://127.0.0.1:1",
                "psr-unreachable", "bearer", null, "provider-tok", "acct", true, List.of(), null, null));
        RepoRef repo = new RepoRef("psr-unreachable", "repo");
        String reviewId = ReviewIds.reviewId(repo, 1);
        projection.registerHeader(reviewId, repo, 1, "Sample", "alice", "a1",
                "feature", "main", "abc", "https://scm.example/x", "bitbucket-cloud", "reviewing",
                ReviewProjection.STAGE_DIFF);

        assertThrows(java.io.UncheckedIOException.class,
                () -> renderer.render(PromptKind.REVIEW, "You are a reviewer.", "Diff:\n{{diff}}", reviewId));
    }

    /**
     * Same failure class as above, but for the description's best-effort fetch: the diff succeeds
     * (so the render gets past the point that would otherwise mask this), and only the PR-metadata
     * call breaks at the connection level. A genuine I/O bug there must not be swallowed into the
     * "unavailable" marker the way a classified SCM error is.
     */
    @Test
    void aGenuineBugFetchingTheDescriptionPropagatesRatherThanBeingMarkedUnavailable() {
        long pr = prCounter.getAndIncrement();
        stubDiff(pr, aResponse().withHeader("Content-Type", "text/plain")
                .withBody(unifiedDiffFor("src/Foo.java", "TESTUSDT placeholder line")));
        scm.stubFor(get(urlEqualTo("/repositories/" + REPO.full() + "/pullrequests/" + pr))
                .willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        String reviewId = registerReview(pr);

        assertThrows(java.io.UncheckedIOException.class,
                () -> renderer.render(PromptKind.REVIEW, "You are a reviewer.", "Diff:\n{{diff}}", reviewId));
    }

    @Test
    void reconcileRendersTheSameRealDiffWithNoInventedFindings() {
        String reviewId = registerReviewWithDiff("src/Foo.java", "TESTUSDT placeholder line");

        PromptValidation.PromptPreview preview = renderer.render(PromptKind.RECONCILE,
                "You are reconciling.", "Prior:\n{{prior_findings}}\n{{diff_kind}}\n{{diff}}", reviewId);

        assertTrue(preview.user().contains("src/Foo.java"));
        // No open findings on a freshly-registered review -- the honest "(none)", never a fabricated one.
        assertTrue(preview.user().contains("BEGIN_UNTRUSTED_DATA\n(none)\nEND_UNTRUSTED_DATA"));
    }

    @Test
    void followupHasNoInventedThreadOrAnchorWithNoOpenFindings() {
        String reviewId = registerReviewWithDiff("src/Foo.java", "TESTUSDT placeholder line");

        PromptValidation.PromptPreview preview = renderer.render(PromptKind.FOLLOWUP,
                "You are replying.", "Anchor: {{anchor}}\nThread:\n{{thread}}\nDiff:\n{{diff}}", reviewId);

        // Neither slot invents a location or a conversation with no open findings to draw on -- both
        // fall back to the same honest, fenced "(none)" (anchor AND thread render it independently).
        assertEquals(2, countOccurrences(preview.user(), "BEGIN_UNTRUSTED_DATA\n(none)\nEND_UNTRUSTED_DATA"));
    }

    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int from = 0;
        while ((from = haystack.indexOf(needle, from)) != -1) {
            count++;
            from += needle.length();
        }
        return count;
    }

    /** Obviously synthetic -- an operator must never mistake this panel for a real diff. */
    private static String oversizedButObviouslySyntheticDiff() {
        int lines = (int) Math.ceil(REVIEW_DIFF_MAX_TOKENS * 3.2 / 20) + 2_000; // comfortably past the clip
        StringBuilder sb = new StringBuilder();
        for (int i = 1; i <= lines; i++) {
            if (i > 1) {
                sb.append('\n');
            }
            sb.append(String.format("// CANARY line %04d", i));
        }
        return sb.toString();
    }

    /** Each added line of {@code content} becomes one hunk line -- a single line for the small
     *  fixtures, thousands for the oversized one. */
    private static String unifiedDiffFor(String path, String content) {
        String[] lines = content.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        sb.append("diff --git a/").append(path).append(" b/").append(path).append('\n');
        sb.append("new file mode 100644\n");
        sb.append("index 0000000..1111111\n");
        sb.append("--- /dev/null\n");
        sb.append("+++ b/").append(path).append('\n');
        sb.append("@@ -0,0 +1,").append(lines.length).append(" @@\n");
        for (String line : lines) {
            sb.append('+').append(line).append('\n');
        }
        return sb.toString();
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
