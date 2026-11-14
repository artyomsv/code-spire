package dev.codespire.scm.gitlab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.scm.PullRequestComment;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Step 8b: a merge request's notes, read with their authors' stable ids; system notes are not comments. */
class GitLabMergeRequestCommentsTest {

    private static final RepoRef REPO = new RepoRef("sandbox", "demo-repo");
    private static final String NOTES = "/projects/sandbox%2Fdemo-repo/merge_requests/42/notes?per_page=100&page=";

    private static WireMockServer server;
    private static GitLabDiffSource source;

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        source = new GitLabDiffSource(new GitLabClient(
                new GitLabConfig("http://localhost:" + server.port(), "test-token"), new ObjectMapper()));
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
    }

    @Test
    void readsNotesWithAuthorIdsAndLeavesOutSystemAndBlankNotes() {
        stubPage(1, """
                [ { "id": 1, "body": "see TEST-1", "system": false,
                    "author": { "id": 101, "username": "TEST-maintainer", "name": "TEST Maintainer" } },
                  { "id": 2, "body": "changed the description", "system": true,
                    "author": { "id": 102, "username": "TEST-system" } },
                  { "id": 3, "body": "", "system": false, "author": { "id": 103, "username": "TEST-blank" } } ]""");

        List<PullRequestComment> comments = source.fetchPullRequestComments(REPO, 42);

        assertEquals(1, comments.size(), comments.toString());
        assertEquals("101", comments.getFirst().author().providerUserId());
        assertEquals("TEST-maintainer", comments.getFirst().author().username());
        assertEquals("see TEST-1", comments.getFirst().body());
    }

    @Test
    void aFullPageReadsTheNextOneAndTheWalkStopsAtThePageCap() {
        StringBuilder full = new StringBuilder("[");
        for (int i = 0; i < 100; i++) {
            full.append(i == 0 ? "" : ",").append("{\"id\":").append(i)
                    .append(",\"body\":\"TEST\",\"system\":false,\"author\":{\"id\":7,\"username\":\"TEST-u\"}}");
        }
        for (int page = 1; page <= GitLabMergeRequestComments.MAX_PAGES + 1; page++) {
            stubPage(page, full + "]");
        }

        List<PullRequestComment> comments = source.fetchPullRequestComments(REPO, 42);

        assertEquals(GitLabMergeRequestComments.MAX_PAGES * 100, comments.size());
        server.verify(0, getRequestedFor(urlEqualTo(NOTES + (GitLabMergeRequestComments.MAX_PAGES + 1))));
    }

    private static void stubPage(int page, String body) {
        server.stubFor(get(urlEqualTo(NOTES + page)).willReturn(aResponse()
                .withHeader("Content-Type", "application/json").withBody(body)));
    }
}
