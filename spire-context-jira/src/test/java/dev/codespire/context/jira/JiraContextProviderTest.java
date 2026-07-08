package dev.codespire.context.jira;

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

import java.util.List;
import java.util.Set;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** JiraContextProvider against a WireMock Jira (v2 issue API). */
class JiraContextProviderTest {

    private static WireMockServer server;
    private static JiraContextProvider provider;
    private static final RepoRef REPO = new RepoRef("sandbox", "demo-repo");

    @BeforeAll
    static void start() {
        server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        provider = new JiraContextProvider(
                new JiraConfig("http://localhost:" + server.port(), "basic", "bot@acme.com", "token", Set.of()),
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

    private static ContextRequest request(Set<String> keys) {
        return new ContextRequest("review::sandbox/demo-repo#7", REPO, 7, "abc123", keys, List.of(), Set.of());
    }

    @Test
    void supportsOnlyWhenTicketKeysArePresent() {
        assertTrue(provider.supports(request(Set.of("CS-42"))));
        assertFalse(provider.supports(request(Set.of())));
    }

    @Test
    void resolvesATicketIntoAContextItemWithRecentComments() {
        server.stubFor(get(urlPathEqualTo("/rest/api/2/issue/CS-42")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"key":"CS-42","fields":{
                            "summary":"Fix the widget",
                            "description":"Steps to reproduce the widget bug.",
                            "status":{"name":"In Progress"},
                            "issuetype":{"name":"Bug"},
                            "comment":{"comments":[
                                {"author":{"displayName":"Ana"},"body":"Use the new API instead."},
                                {"author":{"displayName":"Bob"},"body":"Agreed, ship it."}]}}}
                        """)));

        ContextContribution c = provider.contribute(request(Set.of("CS-42"))).toCompletableFuture().join();

        assertEquals("JIRA", c.source());
        assertEquals(ContribStatus.OK, c.status());
        assertEquals(1, c.items().size());
        ContextItem item = c.items().getFirst();
        assertEquals("JIRA_TICKET", item.kind());
        assertEquals("CS-42 — Fix the widget", item.title());
        assertTrue(item.body().contains("Type: Bug"));
        assertTrue(item.body().contains("Status: In Progress"));
        assertTrue(item.body().contains("Steps to reproduce"));
        assertTrue(item.body().contains("Recent comments:"), "recent comments are appended");
        assertTrue(item.body().contains("Ana: Use the new API instead."));
        assertTrue(item.body().contains("Bob: Agreed, ship it."));
        assertEquals("http://localhost:" + server.port() + "/browse/CS-42", item.uri());
    }

    @Test
    void skipsAnUnresolvableKeyButKeepsTheRest() {
        server.stubFor(get(urlPathEqualTo("/rest/api/2/issue/GONE-1")).willReturn(aResponse().withStatus(404)));
        server.stubFor(get(urlPathEqualTo("/rest/api/2/issue/CS-7")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"key\":\"CS-7\",\"fields\":{\"summary\":\"Real one\"}}")));

        ContextContribution c = provider.contribute(request(orderedKeys("GONE-1", "CS-7")))
                .toCompletableFuture().join();

        assertEquals(ContribStatus.OK, c.status());
        assertEquals(1, c.items().size(), "the 404 key is skipped, the valid key resolves");
        assertEquals("CS-7 — Real one", c.items().getFirst().title());
    }

    @Test
    void emptyWhenNoKeyResolves() {
        server.stubFor(get(urlPathEqualTo("/rest/api/2/issue/GONE-1")).willReturn(aResponse().withStatus(404)));
        ContextContribution c = provider.contribute(request(Set.of("GONE-1"))).toCompletableFuture().join();
        assertEquals(ContribStatus.EMPTY, c.status());
        assertTrue(c.items().isEmpty());
    }

    @Test
    void configuredProjectKeysNarrowWhichCandidatesAreFetched() {
        JiraContextProvider scoped = new JiraContextProvider(
                new JiraConfig("http://localhost:" + server.port(), "basic", "bot@acme.com", "token",
                        Set.of("CS")),
                new ObjectMapper());
        server.stubFor(get(urlPathEqualTo("/rest/api/2/issue/CS-7")).willReturn(aResponse()
                .withHeader("Content-Type", "application/json")
                .withBody("{\"key\":\"CS-7\",\"fields\":{\"summary\":\"Mine\"}}")));

        // AB-9 is off-project — it must never be fetched; only CS-7 resolves.
        ContextContribution c = scoped.contribute(request(orderedKeys("AB-9", "CS-7")))
                .toCompletableFuture().join();

        assertEquals(ContribStatus.OK, c.status());
        assertEquals(1, c.items().size());
        assertEquals("CS-7 — Mine", c.items().getFirst().title());
        server.verify(0, com.github.tomakehurst.wiremock.client.WireMock
                .getRequestedFor(urlPathEqualTo("/rest/api/2/issue/AB-9")));
    }

    @Test
    void authFailureYieldsAnErrorContribution() {
        server.stubFor(get(urlPathEqualTo("/rest/api/2/issue/CS-42")).willReturn(aResponse().withStatus(401)));
        ContextContribution c = provider.contribute(request(Set.of("CS-42"))).toCompletableFuture().join();
        assertEquals(ContribStatus.ERROR, c.status());
        assertTrue(c.items().isEmpty());
    }

    @Test
    void anHtmlSignInPageOnA200IsAnErrorNotAParseCrash() {
        // Jira behind SSO returns its login page (HTTP 200 text/html) — must degrade to ERROR, not throw.
        server.stubFor(get(urlPathEqualTo("/rest/api/2/issue/CS-42")).willReturn(aResponse()
                .withHeader("Content-Type", "text/html")
                .withBody("<!DOCTYPE html><html><head><title>Log in</title></head></html>")));
        ContextContribution c = provider.contribute(request(Set.of("CS-42"))).toCompletableFuture().join();
        assertEquals(ContribStatus.ERROR, c.status());
        assertTrue(c.items().isEmpty());
    }

    /** A deterministic, ordered key set so the 404-then-valid sequence is stable. */
    private static Set<String> orderedKeys(String... keys) {
        return new java.util.LinkedHashSet<>(List.of(keys));
    }
}
