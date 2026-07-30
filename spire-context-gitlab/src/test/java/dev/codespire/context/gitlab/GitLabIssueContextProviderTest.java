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
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
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

    /**
     * A URL names its group exactly, so it must be used as given rather than widened. Widening would
     * try the parent of the linked group first, and if that parent had an epic with the same iid — they
     * are scoped per group — the wrong epic would come back as a success with no error. Asserted on the
     * parent receiving no request at all, because a wrong-epic bug looks identical in the result.
     */
    @Test
    void usesTheGroupAnEpicUrlNamesRatherThanWideningToItsParent() {
        json("/api/v4/groups/acme%2Ftools/epics/7", """
                {"iid":7,"title":"The linked epic","state":"opened","description":"Exact group."}
                """);

        ContextContribution contribution = provider.contribute(request(
                        Set.of("https://gitlab.example.invalid/groups/acme/tools/-/epics/7"), ScmType.GITLAB))
                .toCompletableFuture().join();

        assertEquals(1, contribution.items().size());
        assertTrue(contribution.items().get(0).title().contains("The linked epic"));
        server.verify(0, getRequestedFor(urlPathEqualTo("/api/v4/groups/acme/epics/7")));
    }

    /**
     * Guards {@code ContextWorker}'s level-2 re-mining: a cross-project item's title must not read as
     * a bare, project-relative reference on the next pass, or it would resolve against the review's
     * own project ("acme/tools/widgets") instead of the project it actually names. The round-trip
     * through {@code candidates()}/{@code parse()} shows what the NEXT fetch round would see.
     */
    @Test
    void rendersACrossProjectIssueTitleSoItDoesNotReMineAsABareReference() {
        json("/api/v4/projects/other%2Fproj/issues/9", """
                {"iid":9,"title":"Elsewhere","description":"Lives in another project."}
                """);
        json("/api/v4/projects/other%2Fproj/issues/9/notes", "[]");

        ContextContribution contribution = provider
                .contribute(request(Set.of("other/proj#9"), ScmType.GITLAB))
                .toCompletableFuture().join();

        ContextItem item = contribution.items().get(0);
        assertTrue(item.title().startsWith("other/proj#9"), item.title());

        Set<String> reExtracted = GitLabIssueRefs.candidates(item.title());
        assertEquals(Set.of("other/proj#9"), reExtracted, "must not also surface a bare '#9'");
        GitLabIssueRefs.Ref reparsed = GitLabIssueRefs.parse(reExtracted.iterator().next()).orElseThrow();
        assertFalse(reparsed.isProjectRelative());
        assertEquals("other/proj", reparsed.projectPath());
    }

    /**
     * The companion case: a bare, project-relative reference keeps the bare, shorter title form.
     * Note the asymmetry with the GitHub provider: here the trigger is purely syntactic
     * ({@code pathIsAmbiguous}, i.e. whether the reference was bare or self-naming) rather than a
     * value comparison against {@code request.repo()} — so a reference that explicitly spells out the
     * review's own project (e.g. {@code acme/tools/widgets#12}) still renders qualified, not bare.
     * That is intentional: the discriminator already exists on {@link Target} and needs no re-derivation.
     */
    @Test
    void keepsTheBareTitleForAProjectRelativeReference() {
        json("/api/v4/projects/" + ENCODED + "/issues/12", """
                {"iid":12,"title":"Home","description":"Own project."}
                """);
        json("/api/v4/projects/" + ENCODED + "/issues/12/notes", "[]");

        ContextContribution contribution = provider
                .contribute(request(Set.of("#12"), ScmType.GITLAB))
                .toCompletableFuture().join();

        assertEquals("#12 — Home", contribution.items().get(0).title());
    }

    /**
     * Unlike issues, merge requests and epics have no qualified prose grammar in {@code
     * GitLabIssueRefs} — only bare ({@code !34}, {@code &7}) and URL forms — so a cross-project
     * reference can only be given here as a URL. The qualified title this provider renders
     * ({@code other/proj!34}, {@code other/group&7}) therefore does not round-trip through
     * candidates()/parse() the way the issue case does: it is not extracted as anything at all,
     * because the bare patterns require no preceding word character and the qualifying path violates
     * that. Still safe — nothing is re-mined — just via omission rather than requalification.
     */
    @Test
    void crossProjectMergeRequestAndEpicTitlesDoNotReExtractAtAll() {
        json("/api/v4/projects/other%2Fproj/merge_requests/34", """
                {"iid":34,"title":"Elsewhere MR","description":"Lives in another project."}
                """);
        json("/api/v4/projects/other%2Fproj/merge_requests/34/notes", "[]");
        json("/api/v4/groups/other%2Fgroup/epics/7", """
                {"iid":7,"title":"Elsewhere epic","description":"Lives in another group."}
                """);

        ContextContribution contribution = provider.contribute(request(Set.of(
                        "https://gitlab.example.invalid/other/proj/-/merge_requests/34",
                        "https://gitlab.example.invalid/groups/other/group/-/epics/7"), ScmType.GITLAB))
                .toCompletableFuture().join();

        assertEquals(2, contribution.items().size());
        for (ContextItem item : contribution.items()) {
            assertTrue(GitLabIssueRefs.candidates(item.title()).isEmpty(), item.title());
        }
        assertTrue(contribution.items().stream().anyMatch(i -> i.title().startsWith("other/proj!34")));
        assertTrue(contribution.items().stream().anyMatch(i -> i.title().startsWith("other/group&7")));
    }

    @Test
    void reportsItsSourceUnderTheNameTheAggregatorMerges() {
        assertEquals("GITLAB_ISSUES", provider.source());
        assertEquals("GITLAB_ISSUES", new GitLabIssueReferenceSource().source());
    }
}
