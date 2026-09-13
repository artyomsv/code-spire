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
        return new ProviderInput("P", type, scm.baseUrl(), authKind, authUsername, secret,
                "", true, List.of(), null, null);
    }

    private void stubUser(String body) {
        scm.stubFor(get(urlEqualTo("/user"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json").withBody(body)));
    }

    @Test void validationRepositoryMustBelongToTheAccountsForgeOrigin() {
        assertValidationScope("bitbucket-cloud", "https://other.example.test");
    }

    @Test void storedAccountChecksUseAnExplicitRepositoryBinding() {
        var id = java.util.UUID.randomUUID();
        var selected = new dev.codespire.orchestrator.repository.RepositoryView.Account(id, "TEST-bot", "REVIEWER", null, "no-identity");
        var decoy = new dev.codespire.orchestrator.repository.RepositoryView.Account(java.util.UUID.randomUUID(), "TEST-decoy", "REVIEWER", null, "no-identity");
        resolver.repositories = new dev.codespire.orchestrator.repository.RepositoryRegistry() {
            @Override public List<dev.codespire.orchestrator.repository.RepositoryView> list() {
                return List.of(new dev.codespire.orchestrator.repository.RepositoryView(java.util.UUID.randomUUID(), "bitbucket-cloud", scm.baseUrl(), "TEST-decoy", "TEST-repo", true, 1, decoy, null),
                        new dev.codespire.orchestrator.repository.RepositoryView(java.util.UUID.randomUUID(), "bitbucket-cloud", scm.baseUrl(), "TEST-selected", "TEST-repo", true, 1, selected, null));
            }
        };
        scm.stubFor(get(urlEqualTo("/user")).willReturn(aResponse().withStatus(401)));
        scm.stubFor(get(urlEqualTo("/repositories/TEST-selected?pagelen=1")).willReturn(aResponse().withHeader("Content-Type", "application/json").withBody("{\"values\":[]}")));
        Author owner = org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> resolver.resolveForCheck(
                new ScmProvider(id, "TEST-bot", "bitbucket-cloud", scm.baseUrl(),
                "bearer", null, "TEST-token", "", true, List.of(), null, null, ProviderRole.REVIEWER)));
        assertEquals("", owner.providerUserId());
        scm.verify(getRequestedFor(urlEqualTo("/repositories/TEST-selected?pagelen=1")).withHeader("Authorization", equalTo("Bearer TEST-token")));
        scm.verify(0, getRequestedFor(urlEqualTo("/repositories/TEST-decoy?pagelen=1")));
    }

    @Test void validationRepositoryMustBelongToTheAccountsForgeKind() {
        assertValidationScope("github", scm.baseUrl());
    }

    private void assertValidationScope(String wrongType, String wrongOrigin) {
        stubUser("{\"account_id\":\"TEST-user\",\"nickname\":\"TEST-bot\"}");
        java.util.UUID id = java.util.UUID.randomUUID();
        resolver.repositories = validationRepository(id, "bitbucket-cloud", scm.baseUrl());
        assertEquals("TEST-user", resolver.resolveForRegistration(input("bitbucket-cloud", "bearer", null, "TEST-token"), id).providerUserId());
        scm.resetRequests();
        resolver.repositories = validationRepository(id, wrongType, wrongOrigin);
        assertThrows(IllegalArgumentException.class, () -> resolver.resolveForRegistration(input("bitbucket-cloud", "bearer", null, "TEST-token"), id));
        scm.verify(0, getRequestedFor(urlEqualTo("/user")));
    }

    private static dev.codespire.orchestrator.repository.RepositoryRegistry validationRepository(java.util.UUID id, String type, String origin) {
        return new dev.codespire.orchestrator.repository.RepositoryRegistry() {
            @Override public java.util.Optional<dev.codespire.orchestrator.repository.RepositoryView> get(java.util.UUID requested) {
                assertEquals(id, requested);
                return java.util.Optional.of(new dev.codespire.orchestrator.repository.RepositoryView(id, type, origin, "TEST-ws", "TEST-repo", true, 1, null, null));
            }
        };
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

    @Test
    void bitbucketAccessTokenUsesTheSelectedRepositoryForValidation() {
        // A workspace/repository access token can't call /user (it has no user
        // account) — Bitbucket returns 401 "not supported for this endpoint".
        scm.stubFor(get(urlEqualTo("/user")).willReturn(aResponse().withStatus(401).withBody(
                "{\"type\":\"error\",\"error\":{\"message\":\"Token is invalid, expired, "
                        + "or not supported for this endpoint.\"}}")));
        // ...but it CAN list the workspace's repositories, the capability a review needs.
        scm.stubFor(get(urlEqualTo("/repositories/ws?pagelen=1"))
                .willReturn(aResponse().withHeader("Content-Type", "application/json")
                        .withBody("{ \"values\": [], \"pagelen\": 1 }")));

        var repositoryId = java.util.UUID.randomUUID();
        resolver.repositories = new dev.codespire.orchestrator.repository.RepositoryRegistry() {
            @Override public java.util.Optional<dev.codespire.orchestrator.repository.RepositoryView> get(java.util.UUID id) {
                assertEquals(repositoryId, id);
                return java.util.Optional.of(new dev.codespire.orchestrator.repository.RepositoryView(id,
                        "bitbucket-cloud", scm.baseUrl(), "ws", "TEST-repo", true, 1, null, null));
            }
        };
        Author owner = resolver.resolveForRegistration(input("bitbucket-cloud", "bearer", null, "wtok"), repositoryId);

        assertEquals("", owner.providerUserId(), "an access token has no user account_id to auto-derive");
        scm.verify(getRequestedFor(urlEqualTo("/repositories/ws?pagelen=1"))
                .withHeader("Authorization", equalTo("Bearer wtok")));
    }

    @Test
    void bitbucketBadToken_failsBothUserAndWorkspace_surfacesTheOriginalFailure() {
        scm.stubFor(get(urlEqualTo("/user")).willReturn(aResponse().withStatus(401)));
        scm.stubFor(get(urlEqualTo("/repositories/ws?pagelen=1")).willReturn(aResponse().withStatus(401)));
        var e = assertThrows(BitbucketApiException.class,
                () -> resolver.resolveForRegistration(input("bitbucket-cloud", "bearer", null, "bad")));
        assertEquals(401, e.status(), "a genuinely bad token still fails, via the /user error");
    }

    @Test
    void github_whoamiFailureIsNotMaskedByAWorkspaceFallback() {
        // The workspace fallback is Bitbucket-only; GitHub tokens support /user, so a
        // failure there is real and must surface, not be retried against a workspace.
        scm.stubFor(get(urlEqualTo("/user")).willReturn(aResponse().withStatus(401)));
        var e = assertThrows(GitHubApiException.class,
                () -> resolver.resolveForRegistration(input("github", "bearer", null, "bad")));
        assertEquals(401, e.status());
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
