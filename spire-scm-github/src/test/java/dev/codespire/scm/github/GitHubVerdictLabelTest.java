package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.port.CommentSink.LabelOutcome;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** The verdict label on a GitHub pull request (verdict labels, V120) against a WireMock GitHub. */
class GitHubVerdictLabelTest {

    private static final RepoRef REPO = new RepoRef("TEST-owner", "TEST-repo");
    private static final String LABELS = "/repos/TEST-owner/TEST-repo/issues/42/labels";
    private static final String REPO_LABELS = "/repos/TEST-owner/TEST-repo/labels";
    private static final String PREFIX = "review: ";
    private static final String APPROVED = "review: approved";
    private static final String REQUESTED_PATH = LABELS + "/review%3A%20requested";
    private static final String CHANGES_PATH = LABELS + "/review%3A%20changes-requested";

    private WireMockServer server;
    private GitHubCommentSink sink;

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        sink = new GitHubCommentSink(new GitHubClient(
                new GitHubConfig(server.baseUrl(), "TEST-token-do-not-print", "unused"), new ObjectMapper()), "TEST-bot");
        server.stubFor(delete(anyUrl()).willReturn(aResponse().withStatus(200).withBody("[]")));
        server.stubFor(post(urlEqualTo(LABELS)).willReturn(okJson("[]")));
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private void currentLabels(String json) {
        server.stubFor(get(urlEqualTo(LABELS + "?per_page=100")).willReturn(okJson(json)));
    }

    @Test
    void addsTheTargetAndRemovesEveryOtherReviewLabelKeepingUnrelatedOnes() {
        currentLabels("[{\"name\":\"review: requested\"},{\"name\":\"bug\"},{\"name\":\"review: changes-requested\"},"
                + "{\"name\":\"reviewed\"}]");

        assertEquals(LabelOutcome.APPLIED, sink.setVerdictLabel(REPO, 42, APPROVED, PREFIX));

        server.verify(1, deleteRequestedFor(urlEqualTo(REQUESTED_PATH)));
        server.verify(1, deleteRequestedFor(urlEqualTo(CHANGES_PATH)));
        // "bug" is unrelated, and "reviewed" does not start with the prefix: both stay.
        server.verify(2, deleteRequestedFor(anyUrl()));
        server.verify(1, postRequestedFor(urlEqualTo(LABELS))
                .withRequestBody(equalToJson("{\"labels\":[\"review: approved\"]}")));
    }

    @Test
    void doesNothingWhenTheLabelIsAlreadyTheOnlyReviewLabel() {
        currentLabels("[{\"name\":\"review: approved\"},{\"name\":\"bug\"}]");

        assertEquals(LabelOutcome.ALREADY_SET, sink.setVerdictLabel(REPO, 42, APPROVED, PREFIX));

        server.verify(0, deleteRequestedFor(anyUrl()));
        server.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void keepsTheTargetAndRemovesOnlyTheOthers() {
        currentLabels("[{\"name\":\"review: approved\"},{\"name\":\"review: requested\"}]");

        assertEquals(LabelOutcome.APPLIED, sink.setVerdictLabel(REPO, 42, APPROVED, PREFIX));

        server.verify(1, deleteRequestedFor(urlEqualTo(REQUESTED_PATH)));
        server.verify(1, deleteRequestedFor(anyUrl()));
        server.verify(0, postRequestedFor(anyUrl()));
    }

    @Test
    void createsAMissingLabelThenAddsIt() {
        currentLabels("[]");
        server.stubFor(post(urlEqualTo(LABELS)).inScenario("missing").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(422).withBody("{\"message\":\"Validation Failed\"}"))
                .willSetStateTo("created"));
        server.stubFor(post(urlEqualTo(LABELS)).inScenario("missing").whenScenarioStateIs("created")
                .willReturn(okJson("[{\"name\":\"review: approved\"}]")));
        server.stubFor(post(urlEqualTo(REPO_LABELS)).willReturn(aResponse().withStatus(201).withBody("{}")));

        assertEquals(LabelOutcome.APPLIED, sink.setVerdictLabel(REPO, 42, APPROVED, PREFIX));

        server.verify(1, postRequestedFor(urlEqualTo(REPO_LABELS))
                .withRequestBody(equalToJson("{\"name\":\"review: approved\",\"color\":\"0e8a16\"}")));
        server.verify(2, postRequestedFor(urlEqualTo(LABELS)));
    }

    /** Another writer created it between the two calls: "already exists" is what was asked. */
    @Test
    void aLabelCreatedMeanwhileIsNotAFailure() {
        currentLabels("[]");
        server.stubFor(post(urlEqualTo(LABELS)).inScenario("race").whenScenarioStateIs("Started")
                .willReturn(aResponse().withStatus(404).withBody("{}")).willSetStateTo("exists"));
        server.stubFor(post(urlEqualTo(LABELS)).inScenario("race").whenScenarioStateIs("exists")
                .willReturn(okJson("[]")));
        server.stubFor(post(urlEqualTo(REPO_LABELS)).willReturn(aResponse().withStatus(422).withBody("{}")));

        assertEquals(LabelOutcome.APPLIED, sink.setVerdictLabel(REPO, 42, APPROVED, PREFIX));
    }

    /** A label someone removed after the read is already gone. */
    @Test
    void aLabelAlreadyRemovedIsNotAFailure() {
        currentLabels("[{\"name\":\"review: requested\"}]");
        server.stubFor(delete(urlEqualTo(REQUESTED_PATH)).willReturn(aResponse().withStatus(404).withBody("{}")));

        assertEquals(LabelOutcome.APPLIED, sink.setVerdictLabel(REPO, 42, APPROVED, PREFIX));
    }

    @Test
    void aForbiddenCallIsTheCallersFailure() {
        currentLabels("[]");
        server.stubFor(post(urlEqualTo(LABELS)).willReturn(aResponse().withStatus(403).withBody("{}")));

        GitHubApiException failure = assertThrows(GitHubApiException.class,
                () -> sink.setVerdictLabel(REPO, 42, APPROVED, PREFIX));

        assertEquals(403, failure.status());
        server.verify(0, postRequestedFor(urlEqualTo(REPO_LABELS)));
    }

    @Test
    void aSpaceInALabelIsEncodedAsPercentTwentyNeverAPlus() {
        assertEquals("review%3A%20changes-requested", GitHubLabelApi.encode("review: changes-requested"));
    }
}
