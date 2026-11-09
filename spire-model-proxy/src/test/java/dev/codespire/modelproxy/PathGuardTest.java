package dev.codespire.modelproxy;

import dev.codespire.modelproxy.ProxyRoute.Injection;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PathGuardTest {

    private static final ProxyRoute API_KEY = new ProxyRoute(URI.create("https://api.openai.com"),
            Injection.BEARER, Set.of("/v1/responses", "/v1/models"), List.of());

    private static final ProxyRoute SEAT = new ProxyRoute(URI.create("https://chatgpt.com"),
            Injection.CHATGPT_SEAT, Set.of(), List.of("/backend-api/codex/", "/backend-api/wham"));

    @Test
    void anExactPathIsAllowedAsItIs() {
        assertEquals(Optional.of("/v1/responses"), PathGuard.allowed("/v1/responses", API_KEY));
        assertEquals(Optional.of("/v1/models"), PathGuard.allowed("/v1/models", API_KEY));
    }

    @Test
    void anExactPathIsNotAPrefix() {
        assertEquals(Optional.empty(), PathGuard.allowed("/v1/responses/extra", API_KEY));
        assertEquals(Optional.empty(), PathGuard.allowed("/v1/responses/", API_KEY));
        assertEquals(Optional.empty(), PathGuard.allowed("/v1/files", API_KEY));
        assertEquals(Optional.empty(), PathGuard.allowed("/V1/responses", API_KEY));
    }

    @Test
    void aPrefixMatchesWholeSegmentsOnly() {
        assertEquals(Optional.of("/backend-api/codex/responses"),
                PathGuard.allowed("/backend-api/codex/responses", SEAT));
        assertEquals(Optional.of("/backend-api/wham/accounts/check"),
                PathGuard.allowed("/backend-api/wham/accounts/check", SEAT));
        assertEquals(Optional.empty(), PathGuard.allowed("/backend-api/codexevil", SEAT));
        assertEquals(Optional.empty(), PathGuard.allowed("/backend-api/whamevil/x", SEAT));
        assertEquals(Optional.empty(), PathGuard.allowed("/backend-api/files", SEAT));
    }

    @Test
    void anEscapeIsRefusedBeforeMatching() {
        for (String raw : List.of("/v1/responses/../files", "/v1/responses/..", "/v1/%2e%2e/files",
                "/v1/%2E%2E/files", "/v1/responses%2F..%2Ffiles", "/v1/responses%2f", "/v1/responses%5c",
                "/v1%5Cresponses", "//v1/responses", "/v1//responses", "/v1\\responses", "/v1/./responses",
                "/backend-api/codex/../../v1/files", "/backend-api/codex/./x", "/backend-api/codex/%2e%2e/x",
                "/backend-api/codex/a;b", "/backend-api/codex/a b", "/backend-api/codex//x")) {
            assertEquals(Optional.empty(), PathGuard.allowed(raw, raw.startsWith("/backend") ? SEAT : API_KEY),
                    raw + " must be refused");
        }
    }

    @Test
    void anAllowedPathIsLoggedAsTheRouteThatAllowedIt() {
        assertEquals("/v1/responses", PathGuard.logged("/v1/responses", API_KEY));
        assertEquals("/backend-api/codex/*", PathGuard.logged("/backend-api/codex/responses", SEAT));
        assertEquals("/backend-api/wham/*", PathGuard.logged("/backend-api/wham/users/TEST-jane.doe", SEAT));
        assertEquals("/backend-api/codex/*", PathGuard.logged("/backend-api/codex/" + "a".repeat(5000), SEAT));
    }

    @Test
    void aPathTheRouteDoesNotAllowHasNoLoggedForm() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> PathGuard.logged("/v1/files", API_KEY));
    }

    @Test
    void aPathThatIsNotAnAbsolutePathIsRefused() {
        assertEquals(Optional.empty(), PathGuard.allowed(null, API_KEY));
        assertEquals(Optional.empty(), PathGuard.allowed("", API_KEY));
        assertEquals(Optional.empty(), PathGuard.allowed("v1/responses", API_KEY));
        assertEquals(Optional.empty(), PathGuard.allowed("http://api.openai.com/v1/responses", API_KEY));
    }
}
