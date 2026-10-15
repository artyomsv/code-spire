package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.PostedReview;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ReviewSubmission;
import dev.codespire.contract.scm.ReviewSubmission.InlineComment;
import dev.codespire.contract.scm.Side;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A forge-native review on GitHub (ADR-047 §5.2) against a WireMock GitHub. */
class GitHubReviewSinkTest {

    private static final RepoRef REPO = new RepoRef("TEST-owner", "TEST-repo");
    private static final String REVIEWS = "/repos/TEST-owner/TEST-repo/pulls/42/reviews";
    private static final String HEAD = "b".repeat(40);
    private static final String OLD_HEAD = "a".repeat(40);
    private static final String HEADER = "### Code Spire review (`" + HEAD + "`)";
    private static final String BODY = HEADER + "\n\nTEST summary\n";

    private WireMockServer server;
    private GitHubCommentSink sink;

    @BeforeEach
    void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        sink = new GitHubCommentSink(new GitHubClient(
                new GitHubConfig(server.baseUrl(), "TEST-token-do-not-print", "unused"), new ObjectMapper()), "TEST-bot");
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private static InlineComment single(String path, int newLine) {
        return new InlineComment(new InlineAnchor(path, path, null, newLine, Side.NEW), "TEST " + path);
    }

    private static InlineComment ranged(String path, int start, int end) {
        return new InlineComment(new InlineAnchor(path, path, null, start, Side.NEW, end), "TEST range");
    }

    private static InlineComment oldSide(String path, int oldLine) {
        return new InlineComment(new InlineAnchor(path, path, oldLine, null, Side.OLD), "TEST removed");
    }

    private static String reviewLink(int id) {
        return "https://forge.example.test/TEST-owner/TEST-repo/pull/42#pullrequestreview-" + id;
    }

    private void stubCreated(int id) {
        server.stubFor(post(urlEqualTo(REVIEWS)).willReturn(okJson(
                "{\"id\":" + id + ",\"state\":\"APPROVED\",\"html_url\":\"" + reviewLink(id) + "\"}")));
    }

    private void stubComments(int id, String json) {
        server.stubFor(get(urlEqualTo(REVIEWS + "/" + id + "/comments?per_page=100&page=1")).willReturn(okJson(json)));
    }

    private void stubReviews(String json) {
        server.stubFor(get(urlEqualTo(REVIEWS + "?per_page=100&page=1")).willReturn(okJson(json)));
    }

    @Test
    void postsAnApprovingReviewWithInlineCommentsInOneCall() {
        stubCreated(501);
        stubComments(501, """
                [ {"id":9001,"path":"src/A.java","line":12,"side":"RIGHT","start_line":null},
                  {"id":9002,"path":"src/B.java","line":8,"side":"RIGHT","start_line":5} ]
                """);

        PostedReview posted = sink.postReview(REPO, 42, HEAD, new ReviewSubmission(ReviewVerdict.APPROVE, BODY,
                List.of(single("src/A.java", 12), ranged("src/B.java", 5, 8)))).orElseThrow();

        server.verify(1, postRequestedFor(urlEqualTo(REVIEWS))
                .withRequestBody(matchingJsonPath("$.event", equalTo("APPROVE")))
                .withRequestBody(matchingJsonPath("$.commit_id", equalTo(HEAD)))
                .withRequestBody(matchingJsonPath("$.comments[0].path", equalTo("src/A.java")))
                .withRequestBody(matchingJsonPath("$.comments[0].line", equalTo("12")))
                .withRequestBody(matchingJsonPath("$.comments[0].side", equalTo("RIGHT")))
                .withRequestBody(matchingJsonPath("$.comments[1].start_line", equalTo("5")))
                .withRequestBody(matchingJsonPath("$.comments[1].line", equalTo("8"))));
        assertEquals("501", posted.reviewRef());
        assertEquals(reviewLink(501), posted.reviewUrl(), "the forge's own link, so the core never composes one");
        assertEquals(ReviewVerdict.APPROVE, posted.verdict());
        assertEquals(Map.of("src/A.java:12:NEW", "9001", "src/B.java:5:NEW", "9002"), posted.inlineThreads(),
                "a ranged comment is matched on start_line, not line");
    }

    @Test
    void postsRequestChangesAndComment() {
        stubCreated(502);
        sink.postReview(REPO, 42, HEAD, new ReviewSubmission(ReviewVerdict.REQUEST_CHANGES, BODY, List.of()));
        sink.postReview(REPO, 42, HEAD, new ReviewSubmission(ReviewVerdict.COMMENT, BODY, List.of()));
        server.verify(1, postRequestedFor(urlEqualTo(REVIEWS)).withRequestBody(matchingJsonPath("$.event", equalTo("REQUEST_CHANGES"))));
        server.verify(1, postRequestedFor(urlEqualTo(REVIEWS)).withRequestBody(matchingJsonPath("$.event", equalTo("COMMENT"))));
    }

    @Test
    void anOldSideAnchorIsMatchedOnTheLeftSide() {
        stubCreated(503);
        stubComments(503, "[ {\"id\":9003,\"path\":\"src/A.java\",\"line\":4,\"side\":\"LEFT\",\"start_line\":null} ]");
        PostedReview posted = sink.postReview(REPO, 42, HEAD,
                new ReviewSubmission(ReviewVerdict.COMMENT, BODY, List.of(oldSide("src/A.java", 4)))).orElseThrow();
        server.verify(1, postRequestedFor(urlEqualTo(REVIEWS)).withRequestBody(matchingJsonPath("$.comments[0].side", equalTo("LEFT"))));
        assertEquals(Map.of("src/A.java:4:OLD", "9003"), posted.inlineThreads());
    }

    @Test
    void aSelfAuthoredRefusalRaisesVerdictRefused() {
        server.stubFor(post(urlEqualTo(REVIEWS)).willReturn(aResponse().withStatus(422)
                .withBody("{\"message\":\"Can not approve your own pull request\"}")));
        assertThrows(CommentSink.ReviewVerdictRefused.class, () -> sink.postReview(REPO, 42, HEAD,
                new ReviewSubmission(ReviewVerdict.APPROVE, BODY, List.of(single("src/A.java", 12)))));
    }

    @Test
    void aRejectedAnchorRaisesAnchorRejected() {
        server.stubFor(post(urlEqualTo(REVIEWS)).willReturn(aResponse().withStatus(422)
                .withBody("{\"message\":\"Unprocessable Entity\",\"errors\":[\"pull_request_review_thread.line must be part of the diff\"]}")));
        assertThrows(CommentSink.ReviewAnchorRejected.class, () -> sink.postReview(REPO, 42, HEAD,
                new ReviewSubmission(ReviewVerdict.APPROVE, BODY, List.of(single("src/A.java", 12)))));
    }

    @Test
    void aBodyOnly422IsAPlainFailure() {
        server.stubFor(post(urlEqualTo(REVIEWS)).willReturn(aResponse().withStatus(422)
                .withBody("{\"message\":\"Unprocessable Entity\"}")));
        assertThrows(GitHubApiException.class, () -> sink.postReview(REPO, 42, HEAD,
                new ReviewSubmission(ReviewVerdict.APPROVE, BODY, List.of())));
    }

    @Test
    void aReviewIdMissingFromA2xxIsAFailure() {
        server.stubFor(post(urlEqualTo(REVIEWS)).willReturn(okJson("{}")));
        assertThrows(GitHubApiException.class, () -> sink.postReview(REPO, 42, HEAD,
                new ReviewSubmission(ReviewVerdict.COMMENT, BODY, List.of())));
    }

    @Test
    void readsBackTheInlineCommentIdsAcrossPages() {
        stubCreated(504);
        StringBuilder page1 = new StringBuilder("[");
        for (int i = 0; i < 100; i++) {
            page1.append(i > 0 ? "," : "").append("{\"id\":").append(1000 + i)
                    .append(",\"path\":\"other/File.java\",\"line\":").append(i + 1).append(",\"side\":\"RIGHT\"}");
        }
        page1.append("]");
        stubComments(504, page1.toString());
        server.stubFor(get(urlEqualTo(REVIEWS + "/504/comments?per_page=100&page=2"))
                .willReturn(okJson("[ {\"id\":9004,\"path\":\"src/A.java\",\"line\":12,\"side\":\"RIGHT\"} ]")));
        PostedReview posted = sink.postReview(REPO, 42, HEAD,
                new ReviewSubmission(ReviewVerdict.APPROVE, BODY, List.of(single("src/A.java", 12)))).orElseThrow();
        assertEquals(Map.of("src/A.java:12:NEW", "9004"), posted.inlineThreads());
    }

    @Test
    void findsTheBotsReviewForTheHead() {
        String olderHeader = "### Code Spire review (`" + OLD_HEAD + "`)";
        stubReviews("""
                [ {"id":61,"state":"APPROVED","commit_id":"%s","body":"%s\\n\\nx","user":{"login":"TEST-human"}},
                  {"id":62,"state":"CHANGES_REQUESTED","commit_id":"%s","body":"%s\\n\\nold","user":{"login":"TEST-bot"}},
                  {"id":63,"state":"CHANGES_REQUESTED","commit_id":"%s","body":"%s\\n\\nnew","user":{"login":"TEST-bot"},"html_url":"%s"} ]
                """.formatted(HEAD, HEADER, OLD_HEAD, olderHeader, HEAD, HEADER, reviewLink(63)));
        stubComments(63, "[ {\"id\":9005,\"path\":\"src/A.java\",\"line\":12,\"side\":\"RIGHT\"} ]");
        PostedReview found = sink.findReview(REPO, 42, HEAD,
                new ReviewSubmission(ReviewVerdict.APPROVE, BODY, List.of(single("src/A.java", 12)))).orElseThrow();
        assertEquals("63", found.reviewRef(), "another user's review and the bot's review of an older head are not it");
        assertEquals(reviewLink(63), found.reviewUrl());
        assertEquals(ReviewVerdict.REQUEST_CHANGES, found.verdict(), "the verdict is what the forge holds");
        assertEquals(Map.of("src/A.java:12:NEW", "9005"), found.inlineThreads());
    }

    @Test
    void findReviewIsEmptyWhenTheBotHasNotPostedForThisHead() {
        stubReviews("[ {\"id\":61,\"state\":\"APPROVED\",\"commit_id\":\"%s\",\"body\":\"%s\",\"user\":{\"login\":\"TEST-bot\"}} ]"
                .formatted(OLD_HEAD, HEADER));
        assertTrue(sink.findReview(REPO, 42, HEAD, new ReviewSubmission(ReviewVerdict.APPROVE, BODY, List.of())).isEmpty());
    }

    @Test
    void findReviewIsEmptyWhenTheBotLoginIsUnknown() {
        GitHubCommentSink anonymous = new GitHubCommentSink(new GitHubClient(
                new GitHubConfig(server.baseUrl(), "TEST-token-do-not-print", "unused"), new ObjectMapper()));
        server.stubFor(get(urlEqualTo("/user")).willReturn(aResponse().withStatus(503)));
        assertTrue(anonymous.findReview(REPO, 42, HEAD, new ReviewSubmission(ReviewVerdict.APPROVE, BODY, List.of())).isEmpty(),
                "an unknown login cannot identify the bot's review; the caller posts");
    }

    @Test
    void updatesAReviewBody() {
        server.stubFor(put(urlEqualTo(REVIEWS + "/501")).willReturn(okJson("{\"id\":501}")));
        sink.updateReviewBody(REPO, 42, "501", "TEST new body");
        server.verify(1, putRequestedFor(urlEqualTo(REVIEWS + "/501")).withRequestBody(equalToJson("{\"body\":\"TEST new body\"}")));
    }

    @Test
    void dismissesAStandingChangesRequested() {
        stubReviews("""
                [ {"id":70,"state":"APPROVED","user":{"login":"TEST-bot"}},
                  {"id":77,"state":"CHANGES_REQUESTED","user":{"login":"TEST-bot"}},
                  {"id":78,"state":"COMMENTED","user":{"login":"TEST-bot"}},
                  {"id":79,"state":"CHANGES_REQUESTED","user":{"login":"TEST-human"}} ]
                """);
        server.stubFor(put(urlEqualTo(REVIEWS + "/77/dismissals")).willReturn(okJson("{\"id\":77,\"state\":\"DISMISSED\"}")));
        Optional<String> dismissed = sink.dismissOwnChangesRequested(REPO, 42, "TEST reason");
        assertEquals(Optional.of("77"), dismissed, "a later COMMENTED review does not replace the hold; a human's hold is not ours");
        server.verify(1, putRequestedFor(urlEqualTo(REVIEWS + "/77/dismissals")).withRequestBody(equalToJson("{\"message\":\"TEST reason\"}")));
    }

    @Test
    void noDismissalWhenTheLatestIsApprovedOrDismissed() {
        stubReviews("""
                [ {"id":77,"state":"CHANGES_REQUESTED","user":{"login":"TEST-bot"}},
                  {"id":80,"state":"DISMISSED","user":{"login":"TEST-bot"}} ]
                """);
        assertTrue(sink.dismissOwnChangesRequested(REPO, 42, "TEST reason").isEmpty());
        server.verify(0, putRequestedFor(urlEqualTo(REVIEWS + "/77/dismissals")));
    }
}
