package dev.codespire.orchestrator.provider;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.scm.Author;
import dev.codespire.scm.bitbucket.BitbucketApiException;
import dev.codespire.scm.github.GitHubApiException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The REAL ProviderIdentityResolver against a WireMock SCM (no CDI container,
 * real ProviderClients + real HTTP adapters): whoami resolution per supported
 * provider type with the right endpoint, auth header and field mapping;
 * upstream auth failures surface as the adapter's API exception (the REST layer
 * turns them into a generic 400 — ProviderResourceResolveTest); malformed
 * bodies and unsupported types fail loudly instead of resolving to garbage.
 */
class ProviderIdentityResolverTest {

    private static WireMockServer scm;
    private static ProviderIdentityResolver resolver;

    @BeforeAll
    static void startScm() {
        scm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        scm.start();
        ProviderClients clients = new ProviderClients();
        clients.mapper = new ObjectMapper();
        resolver = new ProviderIdentityResolver();
        resolver.clients = clients;
    }

    @AfterAll
    static void stopScm() {
        scm.stop();
    }

    @BeforeEach
    void resetStubs() {
        scm.resetAll();
    }

    private static ProviderInput input(String type, String authKind, String authUsername, String secret) {
        return new ProviderInput("P", type, scm.baseUrl(), "ws", authKind, authUsername, secret,
                "", true, List.of());
    }

    private void stubUser(String body) {
        scm.stubFor(get(urlEqualTo("/user"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    // --- bitbucket-cloud ---

    @Test
    void bitbucketBearer_resolvesTheTokenOwnerFromGetUser() {
        stubUser("""
                { "account_id": "712020:bot", "username": "spire_bot", "display_name": "Code Spire Bot" }
                """);
        Author owner = resolver.resolve(input("bitbucket-cloud", "bearer", null, "tok-abc"));
        assertEquals("712020:bot", owner.providerUserId(), "account_id is the stable key");
        assertEquals("spire_bot", owner.username());
        assertEquals("Code Spire Bot", owner.displayName());
        scm.verify(getRequestedFor(urlEqualTo("/user"))
                .withHeader("Authorization", equalTo("Bearer tok-abc")));
    }

    @Test
    void bitbucketBasic_sendsUsernameAndSecretAsBasicAuth() {
        stubUser("""
                { "account_id": "712020:bot", "username": "spire_bot", "display_name": "Code Spire Bot" }
                """);
        resolver.resolve(input("bitbucket-cloud", "basic", "bot-user", "app-pass"));
        String expected = "Basic " + Base64.getEncoder()
                .encodeToString("bot-user:app-pass".getBytes(StandardCharsets.UTF_8));
        scm.verify(getRequestedFor(urlEqualTo("/user"))
                .withHeader("Authorization", equalTo(expected)));
    }

    @Test
    void bitbucket_fallsBackToNicknameWhenUsernameIsAbsent() {
        stubUser("""
                { "account_id": "712020:bot", "nickname": "nick", "display_name": "Bot" }
                """);
        Author owner = resolver.resolve(input("bitbucket-cloud", "bearer", null, "tok-abc"));
        assertEquals("nick", owner.username());
    }

    @Test
    void bitbucket_authFailureSurfacesTheAdapterApiException() {
        scm.stubFor(get(urlEqualTo("/user")).willReturn(aResponse().withStatus(401)));
        var e = assertThrows(BitbucketApiException.class,
                () -> resolver.resolve(input("bitbucket-cloud", "bearer", null, "bad-tok")));
        assertEquals(401, e.status());
    }

    @Test
    void bitbucket_malformedBodyFailsInsteadOfResolvingToGarbage() {
        stubUser("{ not json");
        assertThrows(UncheckedIOException.class,
                () -> resolver.resolve(input("bitbucket-cloud", "bearer", null, "tok-abc")));
    }

    // --- github ---

    @Test
    void github_resolvesTheTokenOwnerFromGetUser() {
        stubUser("""
                { "id": 40727, "login": "spire-bot", "name": "Code Spire Bot" }
                """);
        Author owner = resolver.resolve(input("github", "bearer", null, "ghp_tok"));
        assertEquals("40727", owner.providerUserId(), "the numeric id is the stable key");
        assertEquals("spire-bot", owner.username());
        assertEquals("Code Spire Bot", owner.displayName());
        scm.verify(getRequestedFor(urlEqualTo("/user"))
                .withHeader("Authorization", equalTo("Bearer ghp_tok")));
    }

    @Test
    void github_displayNameFallsBackToTheLoginWhenNameIsAbsent() {
        stubUser("""
                { "id": 40727, "login": "spire-bot" }
                """);
        Author owner = resolver.resolve(input("github", "bearer", null, "ghp_tok"));
        assertEquals("spire-bot", owner.displayName());
    }

    @Test
    void github_authFailureSurfacesTheAdapterApiException() {
        scm.stubFor(get(urlEqualTo("/user")).willReturn(aResponse().withStatus(401)));
        var e = assertThrows(GitHubApiException.class,
                () -> resolver.resolve(input("github", "bearer", null, "bad-tok")));
        assertEquals(401, e.status());
    }

    @Test
    void github_serverErrorSurfacesWithItsStatus() {
        scm.stubFor(get(urlEqualTo("/user")).willReturn(aResponse().withStatus(503)));
        var e = assertThrows(GitHubApiException.class,
                () -> resolver.resolve(input("github", "bearer", null, "ghp_tok")));
        assertEquals(503, e.status());
    }

    @Test
    void github_malformedBodyFailsInsteadOfResolvingToGarbage() {
        stubUser("{ not json");
        assertThrows(UncheckedIOException.class,
                () -> resolver.resolve(input("github", "bearer", null, "ghp_tok")));
    }

    // --- unsupported ---

    @Test
    void unsupportedProviderTypeIsRejected() {
        assertThrows(IllegalStateException.class,
                () -> resolver.resolve(input("gitea", "bearer", null, "tok")));
        assertEquals(0, scm.getAllServeEvents().size(), "no call may leave the process for an unknown type");
    }
}
