package dev.codespire.orchestrator.llm;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The check is the ONLY way an LLM credential is ever verified after creation: the pipeline's
 * credential signal rides on ScmApiException, which the LLM adapter does not raise.
 */
@QuarkusTest
class LlmProviderCheckTest {

    @Inject
    LlmProviderResource resource;

    @Inject
    LlmProviderRegistry registry;

    private WireMockServer server;

    @BeforeEach
    void start() {
        server = new WireMockServer(options().dynamicPort());
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop();
    }

    private String createProvider() {
        LlmProviderView view = registry.create(new LlmProviderInput("TEST-llm-" + UUID.randomUUID(),
                "openai", server.baseUrl(), "TEST-KEY", "TEST-MODEL", 0.0, null, true, false));
        return view.id();
    }

    @Test
    void anAcceptedKeyRecordsAPassingCheck() {
        server.stubFor(get("/models").willReturn(aResponse().withStatus(200).withBody("{}")));
        String id = createProvider();

        LlmProviderResource.CheckResult result = resource.check(id);

        assertTrue(result.ok());
        assertTrue(registry.get(UUID.fromString(id)).orElseThrow().lastCheckOk());
    }

    /** A rejected key must come back as a RESULT, not a 400 — the panel needs to store it. */
    @Test
    void aRejectedKeyRecordsAFailingCheckWithoutThrowing() {
        server.stubFor(get("/models").willReturn(aResponse().withStatus(401)));
        String id = createProvider();

        LlmProviderResource.CheckResult result = resource.check(id);

        assertFalse(result.ok());
        assertNotNull(result.detail());
        LlmProviderView reread = registry.get(UUID.fromString(id)).orElseThrow();
        assertFalse(reread.lastCheckOk());
        assertNotNull(reread.lastCheckError());
    }

    /** An unreachable provider is not the same as a bad key, and must not be silent. */
    @Test
    void anUnreachableProviderRecordsAFailingCheck() {
        String id = createProvider();
        server.stop(); // nothing is listening now

        LlmProviderResource.CheckResult result = resource.check(id);

        assertFalse(result.ok());
        assertFalse(registry.get(UUID.fromString(id)).orElseThrow().lastCheckOk());
    }

    @Test
    void aPassingCheckClearsAPreviousRejection() {
        server.stubFor(get("/models").willReturn(aResponse().withStatus(401)));
        String id = createProvider();
        resource.check(id);
        server.stubFor(get("/models").willReturn(aResponse().withStatus(200).withBody("{}")));

        resource.check(id);

        LlmProviderView reread = registry.get(UUID.fromString(id)).orElseThrow();
        assertTrue(reread.lastCheckOk());
        assertEquals(null, reread.lastCheckError());
    }
}
