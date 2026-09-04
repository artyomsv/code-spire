package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.port.PullRequestSink;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.PullRequestRef;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Opening a pull request on GitHub (SCM-MAPPING.md §8) — the first thing in this codebase that does.
 *
 * <p>The property most of these are about is <b>idempotency</b>. What triggers an open is a Kafka
 * record, redelivered on every consumer restart, and by then the push has already happened — so the
 * branch exists and the API would cheerfully open a second pull request from the same head.
 */
class GitHubPullRequestSinkTest {

    private static final RepoRef REPO = new RepoRef("acme", "app");
    private static final String PULLS = "/repos/acme/app/pulls";

    private WireMockServer wireMock;
    private GitHubPullRequestSink sink;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        sink = new GitHubPullRequestSink(new GitHubClient(
                new GitHubConfig(wireMock.baseUrl(), "TEST-token-do-not-print", "unused"),
                new ObjectMapper()));
    }

    @AfterEach
    void stop() {
        wireMock.stop();
    }

    private static PullRequestSink.NewPullRequest request() {
        return new PullRequestSink.NewPullRequest("spire/run_1", "main", "[factory] fix it", "body");
    }

    private void noExistingPullRequest() {
        wireMock.stubFor(get(urlPathEqualTo(PULLS)).willReturn(json("[]")));
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withHeader("Content-Type", "application/json").withBody(body);
    }

    @Test
    void namesItsScm() {
        assertEquals(ScmType.GITHUB, sink.type());
    }

    /** The request GitHub actually receives, asserted whole — a wrong base opens onto the wrong branch. */
    @Test
    void opensAPullRequestWithTheBranchesItWasGiven() {
        noExistingPullRequest();
        wireMock.stubFor(post(urlEqualTo(PULLS))
                .willReturn(json("{\"number\": 42, \"html_url\": \"https://github.com/acme/app/pull/42\"}")));

        PullRequestRef opened = sink.open(REPO, request());

        assertEquals(42L, opened.number());
        assertEquals("https://github.com/acme/app/pull/42", opened.url());
        wireMock.verify(postRequestedFor(urlEqualTo(PULLS)).withRequestBody(equalToJson("""
                {"title": "[factory] fix it", "head": "spire/run_1", "base": "main", "body": "body"}
                """)));
    }

    /**
     * <b>The second call opens nothing and answers the first one's number.</b>
     *
     * <p>This is the whole reason {@code findByHead} is on the port. A redelivery is not an error —
     * the caller needs the number either way, to record it.
     */
    @Test
    void aSecondCallForTheSameBranchOpensNothingAndReturnsTheFirstPullRequest() {
        wireMock.stubFor(get(urlPathEqualTo(PULLS)).willReturn(
                json("[{\"number\": 7, \"html_url\": \"https://github.com/acme/app/pull/7\"}]")));

        PullRequestRef found = sink.open(REPO, request());

        assertEquals(7L, found.number());
        wireMock.verify(0, postRequestedFor(urlEqualTo(PULLS)));
    }

    /**
     * The lookup asks for OPEN pull requests only, and asks about this branch on this repository.
     *
     * <p>Both halves matter. Dropping {@code state=open} would let a MERGED pull request suppress a
     * new one — branch names are reused, and the run's work would then be reviewed by nobody. Dropping
     * the {@code owner:} prefix makes the filter match nothing on GitHub, which fails the other way:
     * a duplicate on every redelivery.
     */
    @Test
    void theLookupAsksForOpenPullRequestsOnThisBranch() {
        noExistingPullRequest();
        wireMock.stubFor(post(urlEqualTo(PULLS)).willReturn(
                json("{\"number\": 1, \"html_url\": \"https://github.com/acme/app/pull/1\"}")));

        sink.open(REPO, request());

        wireMock.verify(getRequestedFor(urlPathEqualTo(PULLS))
                .withQueryParam("state", equalTo("open"))
                .withQueryParam("base", equalTo("main"))
                .withQueryParam("head", equalTo("acme:spire/run_1")));
    }

    /**
     * <b>A pull request from this head onto a DIFFERENT base must not be mistaken for ours.</b>
     *
     * <p>An open pull request is unique per (head, base) pair — GitHub's own duplicate refusal
     * fires only when both match, so a lookup keyed on the head alone is WIDER than the rule the
     * forge enforces. It would answer the pull request aimed at {@code develop}, the caller would
     * record that as this run's delivery, and the pull request that should exist would never be
     * opened. ADR-040's existing-branch mode makes this reachable by design.
     *
     * <p>Driven through the SERVER: the stub answers only when the base filter is present and
     * correct, so a lookup that omits it gets the wrong row and this fails.
     */
    @Test
    void aPullRequestFromThisHeadOntoAnotherBaseIsNotThisRunsDelivery() {
        wireMock.stubFor(get(urlPathEqualTo(PULLS)).withQueryParam("base", equalTo("develop"))
                .willReturn(json("[{\"number\": 99, \"html_url\": \"https://github.com/acme/app/pull/99\"}]")));
        wireMock.stubFor(get(urlPathEqualTo(PULLS)).withQueryParam("base", equalTo("main"))
                .willReturn(json("[]")));
        wireMock.stubFor(post(urlEqualTo(PULLS)).willReturn(
                json("{\"number\": 100, \"html_url\": \"https://github.com/acme/app/pull/100\"}")));

        // Asking about develop finds the existing one; asking about main opens a new one.
        assertEquals(99L, sink.findByHead(REPO, "spire/run_1", "develop").orElseThrow().number());
        assertEquals(100L, sink.open(REPO, request()).number());
    }

    /** A branch with no pull request answers empty, and the stub proves the filter is what decided. */
    @Test
    void aBranchWithNoOpenPullRequestAnswersEmpty() {
        noExistingPullRequest();

        assertEquals(Optional.empty(), sink.findByHead(REPO, "spire/never-opened", "main"));
    }

    /**
     * <b>A read fault THROWS rather than answering empty.</b>
     *
     * <p>Empty is the answer that authorises opening one, so an adapter that cannot reach its forge
     * would open a duplicate on every redelivery — the exact failure the lookup exists to prevent,
     * arriving through the lookup itself. {@code FixRuns} takes the same posture: unknown is not
     * absent.
     */
    @Test
    void aLookupThatCannotReachTheForgeThrowsRatherThanReportingNone() {
        wireMock.stubFor(get(urlPathEqualTo(PULLS)).willReturn(aResponse().withStatus(503)));

        assertThrows(GitHubApiException.class, () -> sink.findByHead(REPO, "spire/run_1", "main"));
    }

    /** And the same fault stops an open, rather than the open proceeding on an unknown. */
    @Test
    void anOpenDoesNotProceedWhenTheLookupFailed() {
        wireMock.stubFor(get(urlPathEqualTo(PULLS)).willReturn(aResponse().withStatus(503)));

        assertThrows(GitHubApiException.class, () -> sink.open(REPO, request()));
        wireMock.verify(0, postRequestedFor(urlEqualTo(PULLS)));
    }

    /**
     * "No commits between" is an OUTCOME, not a fault.
     *
     * <p>It is what a run whose agent changed nothing looks like from here. GitHub reports it as a
     * 422 like every other validation failure, so an operator reading the raw error goes looking for
     * a permission problem that does not exist.
     */
    @Test
    void aBranchWithNothingNewIsReportedAsNothingToProposeRatherThanAsAFailure() {
        noExistingPullRequest();
        wireMock.stubFor(post(urlEqualTo(PULLS)).willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"message\": \"Validation Failed\", \"errors\": "
                        + "[{\"message\": \"No commits between main and spire/run_1\"}]}")));

        PullRequestSink.NothingToPropose nothing = assertThrows(PullRequestSink.NothingToPropose.class,
                () -> sink.open(REPO, request()));
        assertTrue(nothing.getMessage().contains("spire/run_1"), nothing.getMessage());
    }

    /**
     * A race between the lookup and the create answers the winner's pull request.
     *
     * <p>Reachable despite finding first: two deliveries can interleave between the read and the
     * write. The loser must return a number, not a failure.
     *
     * <p><b>No wording is matched for this.</b> An earlier version matched GitHub's "a pull request
     * already exists" text; the case is identifiable by BEHAVIOUR instead — ask the forge whether
     * one exists now, and if it does, a race was the cause whatever the forge called it. The stub
     * below deliberately returns a 422 whose body says nothing recognisable, which the wording
     * version could not have recovered from.
     */
    @Test
    void aRacingSecondDeliveryAnswersThePullRequestTheFirstOpened() {
        wireMock.stubFor(get(urlPathEqualTo(PULLS))
                .inScenario("race").whenScenarioStateIs("Started")
                .willReturn(json("[]")).willSetStateTo("opened"));
        wireMock.stubFor(get(urlPathEqualTo(PULLS))
                .inScenario("race").whenScenarioStateIs("opened")
                .willReturn(json("[{\"number\": 9, \"html_url\": \"https://github.com/acme/app/pull/9\"}]")));
        wireMock.stubFor(post(urlEqualTo(PULLS)).willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"message\": \"Validation Failed\"}")));

        assertEquals(9L, sink.open(REPO, request()).number());
    }

    /**
     * But an already-exists the re-read cannot confirm is still a failure.
     *
     * <p>Inventing a success for a pull request nobody can see would be worse than saying the create
     * failed — the run would be recorded as delivered with no pull request behind it.
     */
    @Test
    void anAlreadyExistsThatCannotBeConfirmedStaysAFailure() {
        noExistingPullRequest();
        wireMock.stubFor(post(urlEqualTo(PULLS)).willReturn(aResponse().withStatus(422)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"message\": \"Validation Failed\"}")));

        assertThrows(GitHubApiException.class, () -> sink.open(REPO, request()));
    }

    /** Every other 4xx stays what the forge said, because the cause is what an operator must read. */
    @Test
    void aPermissionFailureIsReportedAsItselfAndNotAsAnEmptyBranch() {
        noExistingPullRequest();
        wireMock.stubFor(post(urlEqualTo(PULLS)).willReturn(aResponse().withStatus(403)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"message\": \"Resource not accessible by integration\"}")));

        GitHubApiException failed = assertThrows(GitHubApiException.class, () -> sink.open(REPO, request()));
        assertEquals(403, failed.status());
        assertTrue(failed.getMessage().contains("not accessible"), failed.getMessage());
    }

    /**
     * A 2xx carrying no number is a failure, not a pull request.
     *
     * <p>{@code asLong(0)} on an absent field is the fabricated zero ADR-023 names, arriving through a
     * JSON parse. Stored on {@code factory_run.pr_id} it would address nothing and look like a row.
     */
    @Test
    void aSuccessResponseWithNoNumberIsRefused() {
        noExistingPullRequest();
        wireMock.stubFor(post(urlEqualTo(PULLS)).willReturn(json("{\"html_url\": \"https://x/1\"}")));

        assertThrows(GitHubApiException.class, () -> sink.open(REPO, request()));
    }

    /** And one carrying no URL likewise: the URL is the value no caller can rebuild. */
    @Test
    void aSuccessResponseWithNoUrlIsRefused() {
        noExistingPullRequest();
        wireMock.stubFor(post(urlEqualTo(PULLS)).willReturn(json("{\"number\": 42}")));

        assertThrows(GitHubApiException.class, () -> sink.open(REPO, request()));
    }

    /**
     * <b>The nothing-to-propose wording only counts with the status that carries it.</b>
     *
     * <p>The match runs against a 500-character raw body snippet, so an HTML error page from a
     * proxy in front of a self-hosted instance is scanned by the same substring test as a real
     * validation response. An unmatched failure degrades safely — it stays a fault. A falsely
     * matched one reports a run as "the agent changed nothing" when the forge refused for another
     * reason, which is the direction the port exists to prevent.
     */
    @Test
    void thatWordingOnADifferentStatusIsStillAFault() {
        noExistingPullRequest();
        wireMock.stubFor(post(urlEqualTo(PULLS)).willReturn(aResponse().withStatus(502)
                .withHeader("Content-Type", "text/html")
                .withBody("<html><body>Bad gateway: No commits between here and there</body></html>")));

        assertThrows(GitHubApiException.class, () -> sink.open(REPO, request()),
                "a 502 quoting the phrase is not a validation failure");
    }

    /**
     * <b>A fault on the RE-READ must not replace the refusal that started it.</b>
     *
     * <p>An earlier version let the lookup's own exception escape, so an operator saw a failed GET
     * and never learned the create had been refused — the cause replaced by its own diagnosis.
     */
    @Test
    void aFailedReReadKeepsTheOriginalRefusalAndAttachesItsOwn() {
        wireMock.stubFor(get(urlPathEqualTo(PULLS)).inScenario("reread")
                .whenScenarioStateIs("Started").willReturn(json("[]")).willSetStateTo("broken"));
        wireMock.stubFor(get(urlPathEqualTo(PULLS)).inScenario("reread")
                .whenScenarioStateIs("broken").willReturn(aResponse().withStatus(503)));
        wireMock.stubFor(post(urlEqualTo(PULLS)).willReturn(aResponse().withStatus(403)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"message\": \"Resource not accessible by integration\"}")));

        GitHubApiException thrown = assertThrows(GitHubApiException.class,
                () -> sink.open(REPO, request()));

        assertEquals(403, thrown.status(), "the CREATE's refusal is what an operator must read");
        assertEquals(1, thrown.getSuppressed().length, "and the lookup fault is kept, not lost");
    }

    /** A blank head is a caller bug; answering empty would let it open a duplicate every time. */
    @Test
    void aLookupWithNoBranchIsRefused() {
        for (String nothing : new String[] {null, "", "   "}) {
            assertThrows(IllegalArgumentException.class, () -> sink.findByHead(REPO, nothing, "main"),
                    "head=" + nothing);
        }
    }
}
