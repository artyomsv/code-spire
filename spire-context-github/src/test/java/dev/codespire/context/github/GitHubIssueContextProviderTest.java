package dev.codespire.context.github;

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

import java.util.List;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** GitHubIssueContextProvider against a WireMock GitHub. */
class GitHubIssueContextProviderTest {

    private static WireMockServer server;
    private static GitHubIssueContextProvider provider;
    private static final RepoRef REPO = new RepoRef("acme", "widgets");

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        provider = new GitHubIssueContextProvider(
                new GitHubIssueConfig("http://localhost:" + server.port(), "bearer", "TEST-token", Set.of()),
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
        return new ContextRequest("review::acme/widgets#7", REPO, 7, "abc123", references,
                Set.of(), scmType);
    }

    private static void stubIssue(int number, String body) {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/" + number))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    private static void stubNoComments(int number) {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/" + number + "/comments"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("[]")));
    }

    // ---------------------------------------------------------------- the guard

    /**
     * The reason scmType exists. "acme/widgets" is a real repository name on more than one platform,
     * so resolving a bare "#123" for a review that is NOT on GitHub would fetch a real but unrelated
     * issue and present it as context. Asserting on zero requests, not on an empty contribution: a
     * provider that fetched and then discarded would still be wrong.
     */
    @Test
    void resolvesNoBareReferenceForAReviewOnAnotherPlatform() {
        stubIssue(123, "{\"number\":123,\"title\":\"Wrong platform\"}");

        ContextRequest foreign = request(Set.of("#123"), ScmType.BITBUCKET_CLOUD);

        assertFalse(provider.supports(foreign));
        assertEquals(0, server.getAllServeEvents().size());
    }

    /** And declines just as firmly when the platform could not be determined at all. */
    @Test
    void resolvesNoBareReferenceWhenThePlatformIsUnknown() {
        assertFalse(provider.supports(request(Set.of("#123"), null)));
        assertEquals(0, server.getAllServeEvents().size());
    }

    /**
     * A qualified reference names its own repository, so it is unambiguous regardless of where the
     * review runs — an author who writes "acme/widgets#12" on a Bitbucket PR meant that GitHub issue.
     */
    @Test
    void stillResolvesAQualifiedReferenceForAReviewOnAnotherPlatform() {
        assertTrue(provider.supports(request(Set.of("acme/widgets#12"), ScmType.BITBUCKET_CLOUD)));
    }

    @Test
    void ignoresReferencesBelongingToAnotherSource() {
        assertFalse(provider.supports(request(Set.of("PROJ-123"), ScmType.GITHUB)));
    }

    /**
     * One issue referenced two ways must cost one fetch and yield one item. Owners and repositories
     * are case-insensitive on GitHub, so the differently-cased qualified form names the same issue as
     * the bare one.
     */
    @Test
    void collapsesTheSameIssueReferencedTwoWaysIntoOneFetch() {
        stubIssue(12, "{\"number\":12,\"title\":\"Only once\",\"body\":\"One issue.\"}");
        stubNoComments(12);

        ContextContribution contribution = provider
                .contribute(request(Set.of("#12", "Acme/Widgets#12"), ScmType.GITHUB))
                .toCompletableFuture().join();

        assertEquals(1, contribution.items().size(), "one issue, one item");
        assertEquals(1, server.getServeEvents().getServeEvents().stream()
                .filter(e -> e.getRequest().getUrl().equals("/repos/acme/widgets/issues/12"))
                .count(), "one issue referenced twice must not be fetched twice");
    }

    /**
     * The gate is per reference, not per provider: on a review hosted elsewhere, the bare form is
     * unresolvable (its repository would be the wrong host's) while the qualified form names its own
     * repository and must still resolve. One request carrying both proves the distinction.
     */
    @Test
    void gatesTheBareReferenceButNotTheQualifiedOneInTheSameRequest() {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/12")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"number\":12,\"title\":\"Qualified\",\"body\":\"Named its own repo.\"}")));
        stubNoComments(12);

        ContextContribution contribution = provider
                .contribute(request(Set.of("#99", "acme/widgets#12"), ScmType.BITBUCKET_CLOUD))
                .toCompletableFuture().join();

        assertEquals(1, contribution.items().size(), "only the qualified reference resolves");
        assertTrue(contribution.items().get(0).title().contains("#12"));
        server.verify(0, getRequestedFor(urlPathEqualTo("/repos/acme/widgets/issues/99")));
    }

    @Test
    void ignoresARepositoryOutsideTheAllowList() {
        GitHubIssueContextProvider narrowed = new GitHubIssueContextProvider(
                new GitHubIssueConfig("http://localhost:" + server.port(), "bearer", "TEST-token",
                        GitHubIssueRefs.parseRepoAllowList("other")),
                new ObjectMapper());

        assertFalse(narrowed.supports(request(Set.of("#123"), ScmType.GITHUB)));
    }

    // ------------------------------------------------------------- the fetching

    @Test
    void resolvesAnIssueIntoAContextItemWithItsRecentComments() {
        stubIssue(123, """
                {"number":123,"title":"Widget spins backwards","state":"open",
                 "body":"The widget spins backwards above 40rpm.",
                 "labels":[{"name":"bug"},{"name":"priority"}],
                 "html_url":"https://github.com/acme/widgets/issues/123"}
                """);
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/123/comments"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("""
                        [{"body":"Reproduced on the bench rig.","user":{"login":"dana"}},
                         {"body":"Root cause is the gear ratio.","user":{"login":"ines"}}]
                        """)));

        ContextContribution contribution =
                provider.contribute(request(Set.of("#123"), ScmType.GITHUB)).toCompletableFuture().join();

        assertEquals(ContribStatus.OK, contribution.status());
        assertEquals(1, contribution.items().size());
        ContextItem item = contribution.items().get(0);
        assertEquals("ISSUE", item.kind());
        assertTrue(item.title().contains("#123"));
        assertTrue(item.title().contains("Widget spins backwards"));
        assertTrue(item.body().contains("spins backwards above 40rpm"));
        assertTrue(item.body().contains("bug"), "labels carry triage the diff cannot show");
        assertTrue(item.body().contains("Root cause is the gear ratio"), "comments carry the decision");
        assertTrue(item.body().contains("ines"));
        assertEquals("https://github.com/acme/widgets/issues/123", item.uri());
    }

    /**
     * Pull requests share the issues id space. Resolve rather than filter — "supersedes #120" is real
     * context — but label the kind honestly so the model is not told a change request is a requirement.
     */
    @Test
    void labelsAPullRequestAsOneRatherThanCallingItAnIssue() {
        stubIssue(120, """
                {"number":120,"title":"Rework the gearbox","state":"closed","body":"Supersedes the old ratio.",
                 "pull_request":{"url":"https://api.github.com/repos/acme/widgets/pulls/120"},
                 "html_url":"https://github.com/acme/widgets/pull/120"}
                """);
        stubNoComments(120);

        ContextContribution contribution =
                provider.contribute(request(Set.of("#120"), ScmType.GITHUB)).toCompletableFuture().join();

        assertEquals("PULL_REQUEST", contribution.items().get(0).kind());
    }

    /** A typo'd reference must not cost the siblings that did resolve. */
    @Test
    void skipsAMissingReferenceAndKeepsTheRest() {
        stubIssue(1, "{\"number\":1,\"title\":\"Real issue\",\"body\":\"Present.\"}");
        stubNoComments(1);
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/999"))
                .willReturn(aResponse().withStatus(404)
                        .withHeader("Content-Type", "application/json").withBody("{}")));

        ContextContribution contribution = provider
                .contribute(request(Set.of("#1", "#999"), ScmType.GITHUB)).toCompletableFuture().join();

        assertEquals(ContribStatus.OK, contribution.status());
        assertEquals(List.of("Real issue"),
                contribution.items().stream().map(i -> i.title().replaceAll("^#\\d+ — ", "")).toList());
    }

    /** An auth failure applies to every reference, so the contribution is ERROR, not a silent EMPTY. */
    @Test
    void reportsAnAuthFailureAsAnErrorContribution() {
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/1"))
                .willReturn(aResponse().withStatus(401)
                        .withHeader("Content-Type", "application/json").withBody("{}")));

        ContextContribution contribution =
                provider.contribute(request(Set.of("#1"), ScmType.GITHUB)).toCompletableFuture().join();

        assertEquals(ContribStatus.ERROR, contribution.status());
        assertTrue(contribution.items().isEmpty());
    }

    /** Comments are enrichment: losing them must not lose the issue itself. */
    @Test
    void keepsTheIssueWhenItsCommentsCannotBeRead() {
        stubIssue(5, "{\"number\":5,\"title\":\"Readable\",\"body\":\"Body present.\"}");
        server.stubFor(get(urlPathEqualTo("/repos/acme/widgets/issues/5/comments"))
                .willReturn(aResponse().withStatus(500).withBody("{}")));

        ContextContribution contribution =
                provider.contribute(request(Set.of("#5"), ScmType.GITHUB)).toCompletableFuture().join();

        assertEquals(ContribStatus.OK, contribution.status());
        assertTrue(contribution.items().get(0).body().contains("Body present"));
    }

    @Test
    void reportsItsSourceUnderTheNameTheAggregatorMerges() {
        assertEquals("GITHUB_ISSUES", provider.source());
        assertEquals("GITHUB_ISSUES", new GitHubIssueReferenceSource().source());
    }
}
