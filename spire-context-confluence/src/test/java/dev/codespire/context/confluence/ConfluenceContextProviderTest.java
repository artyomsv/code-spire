package dev.codespire.context.confluence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.review.ContribStatus;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** ConfluenceContextProvider against a WireMock Confluence (REST content API). */
class ConfluenceContextProviderTest {

    private static WireMockServer server;
    private static ConfluenceContextProvider provider;
    private static String base;
    private static final RepoRef REPO = new RepoRef("sandbox", "demo-repo");

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        base = "http://localhost:" + server.port();
        provider = new ConfluenceContextProvider(
                new ConfluenceConfig(base, "basic", "bot@acme.com", "token", Set.of()),
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

    private static ContextRequest request(List<String> links) {
        return new ContextRequest("review::sandbox/demo-repo#7", REPO, 7, "abc123", Set.of(), links, Set.of());
    }

    private static String prettyLink(String pageId) {
        return base + "/spaces/ENG/pages/" + pageId + "/Some-Title";
    }

    @Test
    void supportsOnlyWhenAConfluenceLinkIsPresent() {
        assertTrue(provider.supports(request(List.of(prettyLink("12345")))));
        assertFalse(provider.supports(request(List.of())));
        assertFalse(provider.supports(request(List.of("https://github.com/o/r/pull/1"))));
    }

    @Test
    void resolvesAPageIntoAContextItem() {
        server.stubFor(get(urlPathEqualTo("/rest/api/content/12345")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id":"12345","title":"Widget Design",
                         "space":{"key":"ENG","name":"Engineering"},
                         "body":{"storage":{"value":"<p>The widget uses the <code>new API</code>.</p>"}},
                         "_links":{"webui":"/spaces/ENG/pages/12345/Widget+Design"}}
                        """)));

        ContextContribution c = provider.contribute(request(List.of(prettyLink("12345"))))
                .toCompletableFuture().join();

        assertEquals("CONFLUENCE", c.source());
        assertEquals(ContribStatus.OK, c.status());
        assertEquals(1, c.items().size());
        ContextItem item = c.items().getFirst();
        assertEquals("CONFLUENCE_PAGE", item.kind());
        assertEquals("Widget Design (Engineering)", item.title());
        assertTrue(item.body().contains("The widget uses the new API."));
        assertFalse(item.body().contains("<p>"), "storage markup is stripped");
        assertEquals(base + "/spaces/ENG/pages/12345/Widget+Design", item.uri());
    }

    @Test
    void resolvesAViewpageParamLink() {
        server.stubFor(get(urlPathEqualTo("/rest/api/content/67890")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"67890\",\"title\":\"Runbook\",\"body\":{\"storage\":{\"value\":\"steps\"}}}")));

        ContextContribution c = provider.contribute(request(List.of(
                base + "/pages/viewpage.action?pageId=67890"))).toCompletableFuture().join();

        assertEquals(ContribStatus.OK, c.status());
        assertEquals("Runbook", c.items().getFirst().title());
    }

    @Test
    void skipsAnUnresolvablePageButKeepsTheRest() {
        server.stubFor(get(urlPathEqualTo("/rest/api/content/404")).willReturn(aResponse().withStatus(404)));
        server.stubFor(get(urlPathEqualTo("/rest/api/content/7")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"id\":\"7\",\"title\":\"Real one\",\"body\":{\"storage\":{\"value\":\"x\"}}}")));

        ContextContribution c = provider.contribute(request(orderedLinks(prettyLink("404"), prettyLink("7"))))
                .toCompletableFuture().join();

        assertEquals(ContribStatus.OK, c.status());
        assertEquals(1, c.items().size(), "the 404 page is skipped, the valid page resolves");
        assertEquals("Real one", c.items().getFirst().title());
    }

    @Test
    void emptyWhenNoPageResolves() {
        server.stubFor(get(urlPathEqualTo("/rest/api/content/404")).willReturn(aResponse().withStatus(404)));
        ContextContribution c = provider.contribute(request(List.of(prettyLink("404"))))
                .toCompletableFuture().join();
        assertEquals(ContribStatus.EMPTY, c.status());
        assertTrue(c.items().isEmpty());
    }

    @Test
    void configuredSpaceKeysFilterOutOffSpacePages() {
        ConfluenceContextProvider scoped = new ConfluenceContextProvider(
                new ConfluenceConfig(base, "basic", "bot@acme.com", "token", Set.of("ENG")),
                new ObjectMapper());
        server.stubFor(get(urlPathEqualTo("/rest/api/content/9")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"id":"9","title":"Marketing memo","space":{"key":"MKT","name":"Marketing"},
                         "body":{"storage":{"value":"noise"}}}
                        """)));

        ContextContribution c = scoped.contribute(request(List.of(prettyLink("9")))).toCompletableFuture().join();

        assertEquals(ContribStatus.EMPTY, c.status(), "MKT is outside the ENG allow-list");
        assertTrue(c.items().isEmpty());
    }

    @Test
    void authFailureYieldsAnErrorContribution() {
        server.stubFor(get(urlPathEqualTo("/rest/api/content/12345")).willReturn(aResponse().withStatus(401)));
        ContextContribution c = provider.contribute(request(List.of(prettyLink("12345"))))
                .toCompletableFuture().join();
        assertEquals(ContribStatus.ERROR, c.status());
        assertTrue(c.items().isEmpty());
    }

    @Test
    void anHtmlSignInPageOnA200IsAnErrorNotAParseCrash() {
        server.stubFor(get(urlPathEqualTo("/rest/api/content/12345")).willReturn(aResponse()
                .withHeader("Content-Type", "text/html")
                .withBody("<!DOCTYPE html><html><head><title>Log in</title></head></html>")));
        ContextContribution c = provider.contribute(request(List.of(prettyLink("12345"))))
                .toCompletableFuture().join();
        assertEquals(ContribStatus.ERROR, c.status());
        assertTrue(c.items().isEmpty());
    }

    @Test
    void ignoresLinksOnOtherHosts() {
        ContextContribution c = provider.contribute(request(List.of(
                "https://other.example.com/wiki/spaces/ENG/pages/12345/x"))).toCompletableFuture().join();
        assertEquals(ContribStatus.EMPTY, c.status());
        server.verify(0, getRequestedFor(urlPathEqualTo("/rest/api/content/12345")));
    }

    /** A deterministic, ordered link set so the 404-then-valid sequence is stable. */
    private static List<String> orderedLinks(String... links) {
        return List.copyOf(new LinkedHashSet<>(List.of(links)));
    }
}
