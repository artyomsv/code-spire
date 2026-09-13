package dev.codespire.http;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class PinnedJsonWriterTest {
    WireMockServer api;
    PinnedJsonWriter writer;
    PinnedJsonClient reader;
    @BeforeEach void start() {
        api = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        api.start();
        PinnedJsonConfig config = new PinnedJsonConfig("TEST-API", api.baseUrl(), "Bearer TEST-token",
                Map.of("Accept", "application/json"), "TEST-capability required");
        HttpFailures failures = (status, method, path, detail) -> new IllegalStateException("TEST-http-" + status);
        writer = new PinnedJsonWriter(config, new ObjectMapper(), failures);
        reader = new PinnedJsonClient(config, new ObjectMapper(), failures);
    }
    @AfterEach void stop() { api.stop(); }

    @Test void writesUseTheConfiguredOriginAndJsonBody() {
        api.stubFor(post(urlEqualTo("/TEST-comments")).willReturn(okJson("{\"id\":1}")));
        assertEquals(1, writer.post("/TEST-comments", "{\"body\":\"TEST-comment\"}").path("id").asInt());
        api.verify(postRequestedFor(urlEqualTo("/TEST-comments"))
                .withHeader("Authorization", equalTo("Bearer TEST-token"))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("{\"body\":\"TEST-comment\"}")));
    }
    @Test void aWriteIsNotReplayedOnASameOriginRedirect() {
        api.stubFor(post(urlEqualTo("/TEST-start")).willReturn(aResponse().withStatus(307).withHeader("Location", "/TEST-destination")));
        api.stubFor(post(urlEqualTo("/TEST-destination")).willReturn(okJson("{\"id\":2}")));
        assertThrows(IllegalStateException.class, () -> writer.post("/TEST-start", "{}"));
        api.verify(0, postRequestedFor(urlEqualTo("/TEST-destination")));
    }
    @Test void aBodylessWriteIsNotReplayedEither() {
        api.stubFor(post(urlEqualTo("/TEST-start")).willReturn(aResponse().withStatus(307).withHeader("Location", "/TEST-destination")));
        api.stubFor(post(urlEqualTo("/TEST-destination")).willReturn(okJson("{\"id\":2}")));
        assertThrows(IllegalStateException.class, () -> writer.post("/TEST-start", null));
        api.verify(0, postRequestedFor(urlEqualTo("/TEST-destination")));
    }
    @Test void evidenceReadsKeepPaginationHeaders() {
        api.stubFor(get(urlEqualTo("/TEST-evidence")).willReturn(okJson("[]").withHeader("Link", "</TEST-evidence?page=2>; rel=\"next\"")));
        PinnedJsonResponse response = reader.getIdentityResponse("/TEST-evidence");
        assertTrue(response.body().isArray());
        assertEquals("</TEST-evidence?page=2>; rel=\"next\"", response.headers().get("link").getFirst());
    }
    @Test void evidenceReadsCannotFollowAnotherOrigin() {
        WireMockServer foreign = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        foreign.start();
        try {
            api.stubFor(get(urlEqualTo("/TEST-evidence")).willReturn(aResponse().withStatus(302).withHeader("Location", foreign.baseUrl() + "/TEST-evidence")));
            foreign.stubFor(get(urlEqualTo("/TEST-evidence")).willReturn(okJson("[]")));
            assertThrows(IllegalStateException.class, () -> reader.getIdentityResponse("/TEST-evidence"));
            foreign.verify(0, getRequestedFor(urlEqualTo("/TEST-evidence")));
        } finally { foreign.stop(); }
    }
}
