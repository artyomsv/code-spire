package dev.codespire.scm.bitbucket;

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
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Opening a pull request on Bitbucket Cloud (SCM-MAPPING.md §8).
 *
 * <p>Its request body NESTS where the other two are flat, and a wrong nesting does not fail: Jackson's
 * {@code path(...)} reads a missing node as an empty one, so the branch would simply be absent and the
 * forge would answer an error about something else. That is what most of these assert.
 */
class BitbucketCloudPullRequestSinkTest {

    private static final RepoRef REPO = new RepoRef("acme", "app");
    private static final String PRS = "/repositories/acme/app/pullrequests";

    private WireMockServer wireMock;
    private BitbucketCloudPullRequestSink sink;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        sink = new BitbucketCloudPullRequestSink(new BitbucketCloudClient(
                new BitbucketCloudConfig(wireMock.baseUrl(), "TEST-bot",
                        "TEST-app-password-do-not-print", "TEST-secret"),
                new ObjectMapper()));
    }

    @AfterEach
    void stop() {
        wireMock.stop();
    }

    private static PullRequestSink.NewPullRequest request() {
        return new PullRequestSink.NewPullRequest("spire/run_1", "main", "[factory] fix it", "body");
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withHeader("Content-Type", "application/json").withBody(body);
    }

    private void noExistingPullRequest() {
        wireMock.stubFor(get(urlPathEqualTo(PRS)).willReturn(json("{\"values\": []}")));
    }

    private static String opened(long id) {
        return "{\"id\": " + id + ", \"links\": {\"html\": {\"href\": "
                + "\"https://bitbucket.org/acme/app/pull-requests/" + id + "\"}}}";
    }

    @Test
    void namesItsScm() {
        assertEquals(ScmType.BITBUCKET_CLOUD, sink.type());
    }

    /**
     * <b>The nested body, asserted whole.</b>
     *
     * <p>A branch is {@code source.branch.name} here and {@code head} on GitHub and
     * {@code source_branch} on GitLab. Getting the nesting wrong sends a request with no branch in it
     * at all, and the forge answers about a missing field rather than about the one that is wrong.
     */
    @Test
    void opensAPullRequestWithBitbucketsNestedBranchFields() {
        noExistingPullRequest();
        wireMock.stubFor(post(urlEqualTo(PRS)).willReturn(json(opened(42))));

        PullRequestRef ref = sink.open(REPO, request());

        assertEquals(42L, ref.number());
        assertEquals("https://bitbucket.org/acme/app/pull-requests/42", ref.url());
        wireMock.verify(postRequestedFor(urlEqualTo(PRS)).withRequestBody(equalToJson("""
                {"title": "[factory] fix it", "description": "body",
                 "source": {"branch": {"name": "spire/run_1"}},
                 "destination": {"branch": {"name": "main"}}}
                """)));
    }

    /**
     * The URL is read from the nested link, which is the one value no caller can rebuild.
     *
     * <p>Asserted as an exact string rather than "not blank": a wrong path through the JSON yields an
     * empty node, and the ref's own guard would then refuse it for the right reason but the wrong
     * cause. This pins that the correct nesting was read.
     */
    @Test
    void theWebUrlIsReadFromTheNestedHtmlLink() {
        wireMock.stubFor(get(urlPathEqualTo(PRS)).willReturn(json("{\"values\": [" + opened(7) + "]}")));

        assertEquals("https://bitbucket.org/acme/app/pull-requests/7",
                sink.findByHead(REPO, "spire/run_1").orElseThrow().url());
    }

    /**
     * The lookup filters through Bitbucket's own query language, on branch AND state.
     *
     * <p>Dropping the state clause would let a MERGED pull request suppress a new one; dropping the
     * branch clause would answer the newest pull request in the repository, which is worse — the
     * caller would record someone else's pull request as the run's output.
     */
    @Test
    void theLookupFiltersOnTheBranchAndOnTheOpenState() {
        noExistingPullRequest();
        wireMock.stubFor(post(urlEqualTo(PRS)).willReturn(json(opened(1))));

        sink.open(REPO, request());

        wireMock.verify(getRequestedFor(urlPathEqualTo(PRS))
                .withQueryParam("q", containing("source.branch.name=\"spire/run_1\""))
                .withQueryParam("q", containing("state=\"OPEN\"")));
    }

    /** Bitbucket does not refuse a duplicate either, so the lookup is the only guard here too. */
    @Test
    void aSecondCallOpensNothingBecauseThisForgeWouldHappilyOpenASecond() {
        wireMock.stubFor(get(urlPathEqualTo(PRS)).willReturn(json("{\"values\": [" + opened(7) + "]}")));
        wireMock.stubFor(post(urlEqualTo(PRS)).willReturn(json(opened(8))));

        assertEquals(7L, sink.open(REPO, request()).number());
        wireMock.verify(0, postRequestedFor(urlEqualTo(PRS)));
    }

    /** An empty page answers empty — and the envelope is {@code values}, not a bare array. */
    @Test
    void aBranchWithNoOpenPullRequestAnswersEmpty() {
        noExistingPullRequest();

        assertEquals(Optional.empty(), sink.findByHead(REPO, "spire/never-opened"));
    }

    /** Empty is the answer that authorises opening one, so an unreachable forge must not give it. */
    @Test
    void aLookupThatCannotReachTheForgeThrowsRatherThanReportingNone() {
        wireMock.stubFor(get(urlPathEqualTo(PRS)).willReturn(aResponse().withStatus(503)));

        assertThrows(BitbucketApiException.class, () -> sink.findByHead(REPO, "spire/run_1"));
        assertThrows(BitbucketApiException.class, () -> sink.open(REPO, request()));
        wireMock.verify(0, postRequestedFor(urlEqualTo(PRS)));
    }

    /** A run whose agent changed nothing is an outcome, not a permission fault. */
    @Test
    void aBranchWithNothingNewIsReportedAsNothingToPropose() {
        noExistingPullRequest();
        wireMock.stubFor(post(urlEqualTo(PRS)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": {\"message\": \"There are no changes to be pulled\"}}")));

        assertThrows(PullRequestSink.NothingToPropose.class, () -> sink.open(REPO, request()));
    }

    /** A racing second delivery answers the winner's pull request rather than failing. */
    @Test
    void aRacingSecondDeliveryAnswersThePullRequestTheFirstOpened() {
        wireMock.stubFor(get(urlPathEqualTo(PRS)).inScenario("race").whenScenarioStateIs("Started")
                .willReturn(json("{\"values\": []}")).willSetStateTo("opened"));
        wireMock.stubFor(get(urlPathEqualTo(PRS)).inScenario("race").whenScenarioStateIs("opened")
                .willReturn(json("{\"values\": [" + opened(9) + "]}")));
        wireMock.stubFor(post(urlEqualTo(PRS)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": {\"message\": \"A pull request already exists for this branch\"}}")));

        assertEquals(9L, sink.open(REPO, request()).number());
    }

    /** Any other failure stays what the forge said. */
    @Test
    void aPermissionFailureIsReportedAsItself() {
        noExistingPullRequest();
        wireMock.stubFor(post(urlEqualTo(PRS)).willReturn(aResponse().withStatus(403)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\": {\"message\": \"Forbidden\"}}")));

        assertThrows(BitbucketApiException.class, () -> sink.open(REPO, request()));
    }

    /** A 2xx carrying no id, or no link, is a failure rather than a pull request numbered zero. */
    @Test
    void aSuccessResponseMissingEitherHalfIsRefused() {
        noExistingPullRequest();
        wireMock.stubFor(post(urlEqualTo(PRS)).willReturn(json(
                "{\"links\": {\"html\": {\"href\": \"https://bitbucket.org/x/1\"}}}")));
        assertThrows(BitbucketApiException.class, () -> sink.open(REPO, request()));

        wireMock.stubFor(post(urlEqualTo(PRS)).willReturn(json("{\"id\": 42}")));
        assertThrows(BitbucketApiException.class, () -> sink.open(REPO, request()));
    }

    @Test
    void aLookupWithNoBranchIsRefused() {
        for (String nothing : new String[] {null, "", "   "}) {
            assertThrows(IllegalArgumentException.class, () -> sink.findByHead(REPO, nothing),
                    "head=" + nothing);
        }
    }
}
