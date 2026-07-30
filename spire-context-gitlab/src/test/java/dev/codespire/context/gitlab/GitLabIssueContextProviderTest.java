package dev.codespire.context.gitlab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.review.ContribStatus;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GitLabIssueContextProvider against a WireMock GitLab (v4). */
class GitLabIssueContextProviderTest {

    private static WireMockServer server;
    private static GitLabIssueContextProvider provider;
    private static final RepoRef REPO = new RepoRef("acme/tools", "widgets");
    private static final String ENCODED = "acme%2Ftools%2Fwidgets";

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        provider = new GitLabIssueContextProvider(
                new GitLabIssueConfig("http://localhost:" + server.port(), "bearer", "TEST-token", Set.of()),
                new ObjectMapper());
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
    }

    private static ContextRequest request(Set<String> references, ScmType scmType) {
        return new ContextRequest("review::acme/tools/widgets#7", REPO, 7, "abc123", references,
                Set.of(), scmType);
    }

    private static void json(String path, String body) {
        server.stubFor(get(urlPathEqualTo(path))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static void status(String path, int code) {
        server.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(code)
                .withHeader("Content-Type", "application/json").withBody("{}")));
    }

    // ---------------------------------------------------------------- the guard

    /** The same guard as the GitHub provider, for the same reason — see the spec's hazard section. */
    @Test
    void resolvesNoBareReferenceForAReviewOnAnotherPlatform() {
        json("/api/v4/projects/" + ENCODED + "/issues/123", "{\"iid\":123,\"title\":\"Wrong platform\"}");

        assertFalse(provider.supports(request(Set.of("#123"), ScmType.GITHUB)));
        assertEquals(0, server.getAllServeEvents().size());
    }

    @Test
    void resolvesNoBareReferenceWhenThePlatformIsUnknown() {
        assertFalse(provider.supports(request(Set.of("#123"), null)));
    }

    @Test
    void stillResolvesAQualifiedReferenceForAReviewOnAnotherPlatform() {
        assertTrue(provider.supports(request(Set.of("acme/tools/widgets#12"), ScmType.GITHUB)));
    }

    // ------------------------------------------------------------- the fetching

    @Test
    void resolvesAnIssueWithItsNonSystemNotes() {
        json("/api/v4/projects/" + ENCODED + "/issues/12", """
                {"iid":12,"title":"Widget spins backwards","state":"opened",
                 "description":"Spins backwards above 40rpm.","labels":["bug"],
                 "web_url":"https://gitlab.com/acme/tools/widgets/-/issues/12"}
                """);
        json("/api/v4/projects/" + ENCODED + "/issues/12/notes", """
                [{"body":"changed title from foo to bar","system":true,"author":{"username":"bot"}},
                 {"body":"Root cause is the gear ratio.","system":false,"author":{"username":"ines"}}]
                """);

        ContextContribution contribution =
                provider.contribute(request(Set.of("#12"), ScmType.GITLAB)).toCompletableFuture().join();

        assertEquals(ContribStatus.OK, contribution.status());
        ContextItem item = contribution.items().get(0);
        assertEquals("ISSUE", item.kind());
        assertTrue(item.body().contains("Root cause is the gear ratio"));
        assertFalse(item.body().contains("changed title"), "system notes are activity noise, not context");
        assertEquals("https://gitlab.com/acme/tools/widgets/-/issues/12", item.uri());
    }

    @Test
    void resolvesAMergeRequestReferenceUnderItsOwnKind() {
        json("/api/v4/projects/" + ENCODED + "/merge_requests/34", """
                {"iid":34,"title":"Rework the gearbox","state":"merged","description":"Supersedes the ratio.",
                 "web_url":"https://gitlab.com/acme/tools/widgets/-/merge_requests/34"}
                """);
        json("/api/v4/projects/" + ENCODED + "/merge_requests/34/notes", "[]");

        ContextContribution contribution =
                provider.contribute(request(Set.of("!34"), ScmType.GITLAB)).toCompletableFuture().join();

        assertEquals("PULL_REQUEST", contribution.items().get(0).kind());
    }

    /** An epic's owning group is not stated by the project path, so the nearest ancestor is tried first. */
    @Test
    void findsAnEpicOnTheNearestAncestorGroup() {
        json("/api/v4/groups/acme%2Ftools/epics/7", """
                {"iid":7,"title":"Gearbox overhaul","state":"opened","description":"Umbrella work.",
                 "web_url":"https://gitlab.com/groups/acme/tools/-/epics/7"}
                """);

        ContextContribution contribution =
                provider.contribute(request(Set.of("&7"), ScmType.GITLAB)).toCompletableFuture().join();

        assertEquals("EPIC", contribution.items().get(0).kind());
    }

    @Test
    void fallsBackToTheTopLevelGroupWhenTheNearestAncestorHasNoSuchEpic() {
        status("/api/v4/groups/acme%2Ftools/epics/7", 404);
        json("/api/v4/groups/acme/epics/7", """
                {"iid":7,"title":"Gearbox overhaul","state":"opened","description":"Umbrella work."}
                """);

        ContextContribution contribution =
                provider.contribute(request(Set.of("&7"), ScmType.GITLAB)).toCompletableFuture().join();

        assertEquals(1, contribution.items().size());
        assertEquals("EPIC", contribution.items().get(0).kind());
    }

    /**
     * Epics are a GitLab Premium feature, so a free-tier instance answers 403. That must cost the
     * epic only — an operator who cannot read epics must still get their issue context.
     */
    @Test
    void skipsAnEpicTheInstanceCannotServeWithoutLosingTheIssues() {
        status("/api/v4/groups/acme%2Ftools/epics/7", 403);
        status("/api/v4/groups/acme/epics/7", 403);
        json("/api/v4/projects/" + ENCODED + "/issues/12",
                "{\"iid\":12,\"title\":\"Real issue\",\"description\":\"Present.\"}");
        json("/api/v4/projects/" + ENCODED + "/issues/12/notes", "[]");

        ContextContribution contribution = provider
                .contribute(request(Set.of("&7", "#12"), ScmType.GITLAB)).toCompletableFuture().join();

        assertEquals(ContribStatus.OK, contribution.status());
        assertEquals(1, contribution.items().size());
        assertEquals("ISSUE", contribution.items().get(0).kind());
    }

    /** An auth failure on an issue is not the same as a Premium-gated epic: it marks everything. */
    @Test
    void reportsAnAuthFailureOnAnIssueAsAnErrorContribution() {
        status("/api/v4/projects/" + ENCODED + "/issues/12", 401);

        ContextContribution contribution =
                provider.contribute(request(Set.of("#12"), ScmType.GITLAB)).toCompletableFuture().join();

        assertEquals(ContribStatus.ERROR, contribution.status());
    }

    @Test
    void skipsAMissingIssueAndKeepsTheRest() {
        status("/api/v4/projects/" + ENCODED + "/issues/999", 404);
        json("/api/v4/projects/" + ENCODED + "/issues/12",
                "{\"iid\":12,\"title\":\"Real issue\",\"description\":\"Present.\"}");
        json("/api/v4/projects/" + ENCODED + "/issues/12/notes", "[]");

        ContextContribution contribution = provider
                .contribute(request(Set.of("#12", "#999"), ScmType.GITLAB)).toCompletableFuture().join();

        assertEquals(ContribStatus.OK, contribution.status());
        assertEquals(1, contribution.items().size());
    }

    @Test
    void reportsItsSourceUnderTheNameTheAggregatorMerges() {
        assertEquals("GITLAB_ISSUES", provider.source());
        assertEquals("GITLAB_ISSUES", new GitLabIssueReferenceSource().source());
    }
}
