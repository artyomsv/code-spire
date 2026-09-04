package dev.codespire.scm.gitlab;

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
 * Opening a merge request on GitLab (SCM-MAPPING.md §8).
 *
 * <p>Two of §8's divergences are tested here rather than described, because both are silent when
 * wrong: the {@code iid}/{@code id} pair, and the fact that this forge does NOT refuse a duplicate.
 */
class GitLabPullRequestSinkTest {

    private static final RepoRef REPO = new RepoRef("acme", "app");
    /** GitLab addresses a project by URL-encoded {@code namespace/path}. */
    private static final String MRS = "/projects/acme%2Fapp/merge_requests";

    private WireMockServer wireMock;
    private GitLabPullRequestSink sink;

    @BeforeEach
    void start() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        sink = new GitLabPullRequestSink(new GitLabClient(
                new GitLabConfig(wireMock.baseUrl(), "TEST-token-do-not-print"),
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

    private void noExistingMergeRequest() {
        wireMock.stubFor(get(urlPathEqualTo(MRS)).willReturn(json("[]")));
    }

    @Test
    void namesItsScm() {
        assertEquals(ScmType.GITLAB, sink.type());
    }

    /** The nested-free body GitLab takes, asserted whole — its field names match no other forge. */
    @Test
    void opensAMergeRequestWithGitLabsOwnFieldNames() {
        noExistingMergeRequest();
        wireMock.stubFor(post(urlEqualTo(MRS)).willReturn(json(
                "{\"id\": 90210, \"iid\": 42, \"web_url\": \"https://gitlab.com/acme/app/-/merge_requests/42\"}")));

        PullRequestRef opened = sink.open(REPO, request());

        assertEquals(42L, opened.number());
        wireMock.verify(postRequestedFor(urlEqualTo(MRS)).withRequestBody(equalToJson("""
                {"source_branch": "spire/run_1", "target_branch": "main",
                 "title": "[factory] fix it", "description": "body"}
                """)));
    }

    /**
     * <b>{@code iid}, never {@code id} — and the stub carries both so the wrong one is reachable.</b>
     *
     * <p>{@code id} is a GitLab-global identifier; {@code iid} is the number in the URL and in every
     * API path. Reading {@code id} produces a number that looks entirely valid, stores cleanly in
     * {@code factory_run.pr_id}, and addresses another project's merge request. Nothing fails.
     *
     * <p>A stub carrying only {@code iid} would let the mutation pass by returning zero, which the
     * ref refuses for an unrelated reason — so the two values must both be present AND differ.
     */
    @Test
    void theNumberIsTheProjectScopedIidAndNotTheGlobalId() {
        wireMock.stubFor(get(urlPathEqualTo(MRS)).willReturn(json(
                "[{\"id\": 90210, \"iid\": 42, \"web_url\": \"https://gitlab.com/acme/app/-/merge_requests/42\"}]")));

        assertEquals(42L, sink.findByHead(REPO, "spire/run_1").orElseThrow().number());
    }

    /**
     * The lookup asks for {@code opened}, which is GitLab's spelling and nobody else's.
     *
     * <p>A wrong value is not an error on this API — an unrecognised state filter is ignored and
     * everything comes back, so a merged merge request would suppress a new one and nothing would
     * say why. That is the failure this assertion exists for, and it is invisible without it.
     */
    @Test
    void theLookupUsesGitLabsOwnSpellingOfTheOpenState() {
        noExistingMergeRequest();
        wireMock.stubFor(post(urlEqualTo(MRS)).willReturn(json(
                "{\"iid\": 1, \"web_url\": \"https://gitlab.com/acme/app/-/merge_requests/1\"}")));

        sink.open(REPO, request());

        wireMock.verify(getRequestedFor(urlPathEqualTo(MRS))
                .withQueryParam("state", equalTo("opened"))
                .withQueryParam("source_branch", equalTo("spire/run_1")));
    }

    /**
     * <b>GitLab does NOT refuse a duplicate, so the find-first call is the only guard.</b>
     *
     * <p>Unlike GitHub, a second create from the same source branch succeeds and opens a second merge
     * request. The stub therefore answers 201 to a POST — if the lookup did not stop it, this test
     * would see two merge requests and the caller would too.
     */
    @Test
    void aSecondCallOpensNothingBecauseThisForgeWouldHappilyOpenASecond() {
        wireMock.stubFor(get(urlPathEqualTo(MRS)).willReturn(json(
                "[{\"iid\": 7, \"web_url\": \"https://gitlab.com/acme/app/-/merge_requests/7\"}]")));
        wireMock.stubFor(post(urlEqualTo(MRS)).willReturn(json(
                "{\"iid\": 8, \"web_url\": \"https://gitlab.com/acme/app/-/merge_requests/8\"}")));

        assertEquals(7L, sink.open(REPO, request()).number());
        wireMock.verify(0, postRequestedFor(urlEqualTo(MRS)));
    }

    @Test
    void aBranchWithNoOpenMergeRequestAnswersEmpty() {
        noExistingMergeRequest();

        assertEquals(Optional.empty(), sink.findByHead(REPO, "spire/never-opened"));
    }

    /** Empty is the answer that authorises opening one, so an unreachable forge must not give it. */
    @Test
    void aLookupThatCannotReachTheForgeThrowsRatherThanReportingNone() {
        wireMock.stubFor(get(urlPathEqualTo(MRS)).willReturn(aResponse().withStatus(503)));

        assertThrows(GitLabApiException.class, () -> sink.findByHead(REPO, "spire/run_1"));
        assertThrows(GitLabApiException.class, () -> sink.open(REPO, request()));
        wireMock.verify(0, postRequestedFor(urlEqualTo(MRS)));
    }

    /** A run whose agent changed nothing is an outcome, not a permission fault. */
    @Test
    void aBranchWithNothingNewIsReportedAsNothingToPropose() {
        noExistingMergeRequest();
        wireMock.stubFor(post(urlEqualTo(MRS)).willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"message\": [\"No changes between spire/run_1 and main\"]}")));

        assertThrows(PullRequestSink.NothingToPropose.class, () -> sink.open(REPO, request()));
    }

    /** A racing second delivery answers the winner's merge request rather than failing. */
    @Test
    void aRacingSecondDeliveryAnswersTheMergeRequestTheFirstOpened() {
        wireMock.stubFor(get(urlPathEqualTo(MRS)).inScenario("race").whenScenarioStateIs("Started")
                .willReturn(json("[]")).willSetStateTo("opened"));
        wireMock.stubFor(get(urlPathEqualTo(MRS)).inScenario("race").whenScenarioStateIs("opened")
                .willReturn(json("[{\"iid\": 9, \"web_url\": \"https://gitlab.com/acme/app/-/merge_requests/9\"}]")));
        wireMock.stubFor(post(urlEqualTo(MRS)).willReturn(aResponse().withStatus(409)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"message\": [\"Another open merge request already exists for this source branch\"]}")));

        assertEquals(9L, sink.open(REPO, request()).number());
    }

    /** Any other failure stays what the forge said — the cause is what an operator must read. */
    @Test
    void aPermissionFailureIsReportedAsItself() {
        noExistingMergeRequest();
        wireMock.stubFor(post(urlEqualTo(MRS)).willReturn(aResponse().withStatus(403)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"message\": \"403 Forbidden\"}")));

        assertThrows(GitLabApiException.class, () -> sink.open(REPO, request()));
    }

    /** A 2xx carrying no iid is a failure, not a merge request numbered zero. */
    @Test
    void aSuccessResponseWithNoIidIsRefused() {
        noExistingMergeRequest();
        wireMock.stubFor(post(urlEqualTo(MRS)).willReturn(json(
                "{\"id\": 90210, \"web_url\": \"https://gitlab.com/x/-/merge_requests/1\"}")));

        assertThrows(GitLabApiException.class, () -> sink.open(REPO, request()));
    }

    @Test
    void aLookupWithNoBranchIsRefused() {
        for (String nothing : new String[] {null, "", "   "}) {
            assertThrows(IllegalArgumentException.class, () -> sink.findByHead(REPO, nothing),
                    "head=" + nothing);
        }
    }

    /** A nested namespace is encoded whole — GitLab projects legitimately nest several deep. */
    @Test
    void aNestedProjectNamespaceIsAddressedCorrectly() {
        RepoRef nested = new RepoRef("acme/platform/team", "app");
        String path = "/projects/acme%2Fplatform%2Fteam%2Fapp/merge_requests";
        wireMock.stubFor(get(urlPathEqualTo(path)).willReturn(json("[]")));

        assertTrue(sink.findByHead(nested, "spire/run_1").isEmpty());
        wireMock.verify(getRequestedFor(urlPathEqualTo(path)));
    }
}
