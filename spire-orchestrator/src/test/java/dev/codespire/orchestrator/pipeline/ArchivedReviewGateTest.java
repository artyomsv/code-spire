package dev.codespire.orchestrator.pipeline;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.provider.ProviderInput;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ArchiveOutcome;
import dev.codespire.orchestrator.readmodel.ReviewFixtures;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import jakarta.ws.rs.ClientErrorException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static dev.codespire.orchestrator.readmodel.ReviewFixtures.REPO;
import static dev.codespire.orchestrator.readmodel.ReviewFixtures.WS;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Neither re-run nor manual re-registration is an integration event, so neither passes through
 * {@link IntegrationSaga}, where every other archival gate lives. A gate placed only in the saga
 * would leave both of these REST paths free to resurrect an archived review.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class ArchivedReviewGateTest {

    @Inject
    ReviewProjection projection;

    @Inject
    ProviderRegistry providers;

    private WireMockServer wm;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    /**
     * {@code rerun}'s first act is {@code clearWorkerIdempotency}, which deletes ALL worker claims for
     * the review, including the once-ever archived-notice claim. Driven with hand-rolled fakes (the
     * same style as {@link IntegrationSagaPolicyTest}) rather than the real collaborators, so the
     * assertion is exactly "the destructive call never happened" rather than a side effect of some
     * other fixture gap.
     */
    @Test
    void aRerunOfAnArchivedReviewIsRefusedBeforeClearingWorkerClaims() {
        List<String> clearedClaims = new ArrayList<>();
        ReviewRerunService service = new ReviewRerunService();
        service.projection = fakeArchivedProjection(clearedClaims);
        service.lifecycle = fakeLifecycleThatStartsARun();
        service.commands = new CommandsEmitter() {
            @Override
            public void emit(ActionCommand command) {
                throw new AssertionError("an archived rerun must never dispatch a command");
            }
        };
        service.workerCredentials = fakeWorkerCredentials();

        assertThrows(ClientErrorException.class,
                () -> service.rerun("TEST-GATE-WS", "TEST-GATE-REPO", 1L));

        assertTrue(clearedClaims.isEmpty(),
                "the archived gate must run before clearWorkerIdempotency, or the once-ever "
                        + "archived-notice claim it protects would be wiped along with everything else");
    }

    @Test
    void registeringAnArchivedPrIs409NotASilentSuccess() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
        assertEquals(ArchiveOutcome.ARCHIVED, projection.archiveReview(WS, REPO, pr));
        providers.create(new ProviderInput("TEST-CANARY-PROVIDER", "bitbucket-cloud", wm.baseUrl(),
                WS, "bearer", null, "TEST-TOKEN", "acct", true, List.of(), null, null));
        wm.stubFor(get(urlEqualTo("/repositories/" + WS + "/" + REPO + "/pullrequests/" + pr))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        { "id": %d, "title": "T", "description": "",
                          "source": { "branch": {"name": "f"}, "commit": {"hash": "abc123"} },
                          "destination": { "branch": {"name": "main"} },
                          "author": { "account_id": "a", "nickname": "n", "display_name": "N" },
                          "links": { "html": {"href": "http://x"} } }
                        """.formatted(pr))));

        given().contentType("application/json")
                .body(Map.of("workspace", WS, "slug", REPO, "pr", pr, "providerType", "bitbucket-cloud"))
                .when().post("/api/reviews/register")
                .then().statusCode(409).body(containsString("archived"));
    }

    private static ReviewProjection fakeArchivedProjection(List<String> clearedClaims) {
        return new ReviewProjection() {
            @Override
            public boolean archived(String reviewId) {
                return true;
            }

            @Override
            public Optional<String> commitOf(String reviewId) {
                return Optional.of("TESTSHA1");
            }

            @Override
            public void clearWorkerIdempotency(String reviewId) {
                clearedClaims.add(reviewId);
            }

            @Override
            public void markRerunStarted(String reviewId) {
            }
        };
    }

    /** A decider that claims a run, so a missing gate would be exercised end to end, not skipped early. */
    private static ReviewLifecycleService fakeLifecycleThatStartsARun() {
        return new ReviewLifecycleService() {
            @Override
            public List<DomainEvent> handle(String reviewId, RecordCommand command) {
                return List.of(new DomainEvent.ReviewRequested("TESTSHA1", "rerun"));
            }
        };
    }

    private static WorkerCredentials fakeWorkerCredentials() {
        return new WorkerCredentials() {
            @Override
            public Optional<String> packForReview(String reviewId) {
                return Optional.of("TEST-PACKED-CREDENTIAL");
            }
        };
    }
}
