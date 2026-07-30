package dev.codespire.orchestrator.llm;

import com.github.tomakehurst.wiremock.WireMockServer;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
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
import static org.junit.jupiter.api.Assertions.assertNull;
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

    /**
     * An unreachable provider is not the same as a bad key, and must not be silent — but it is
     * also not proof the key is bad, so it must not write FALSE (an outage the operator's own
     * host cannot reach would otherwise light up a row that fixing the network could never
     * clear).
     */
    @Test
    void anUnreachableProviderRecordsAFailingCheckWithoutWritingFalse() {
        String id = createProvider();
        server.stop(); // nothing is listening now

        LlmProviderResource.CheckResult result = resource.check(id);

        assertFalse(result.ok(), "the returned outcome still reports the failure");
        assertNull(registry.get(UUID.fromString(id)).orElseThrow().lastCheckOk(),
                "an unreachable provider is not a rejected credential and must not write FALSE");
    }

    /** The same non-write rule must not clear a genuine prior pass either. */
    @Test
    void anUnreachableProviderDoesNotClearAPriorPassingCheck() {
        server.stubFor(get("/models").willReturn(aResponse().withStatus(200).withBody("{}")));
        String id = createProvider();
        resource.check(id); // records a pass
        server.stop(); // now unreachable

        resource.check(id);

        assertTrue(registry.get(UUID.fromString(id)).orElseThrow().lastCheckOk(),
                "an unreachable provider must not clear a prior passing check");
    }

    /** A 5xx is as inconclusive as an unreachable provider, and must not write FALSE either. */
    @Test
    void a500CheckFailureDoesNotWriteFalse() {
        server.stubFor(get("/models").willReturn(aResponse().withStatus(500)));
        String id = createProvider();

        LlmProviderResource.CheckResult result = resource.check(id);

        assertFalse(result.ok());
        assertNull(registry.get(UUID.fromString(id)).orElseThrow().lastCheckOk(),
                "an unexpected non-auth status is not a rejected credential and must not write FALSE");
    }

    /**
     * 403 IS treated as a rejection for LLM providers — deliberately unlike the SCM check, since
     * these vendors signal throttling with 429 rather than 403. Guards against the two rules
     * drifting together.
     */
    @Test
    void a403IsTreatedAsARejectedKey() {
        server.stubFor(get("/models").willReturn(aResponse().withStatus(403)));
        String id = createProvider();

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

    // ---- create/update: a successful save is itself a passing check --------------------------
    //
    // validator.ping(...) is an authoritative probe: it throws and 400s the save if the key is
    // bad, so a successful save already proved the key works. Neither create nor update used to
    // record that, so a rejected key stayed rejected even after the operator pasted a working one
    // and saved successfully.

    @Test
    void creatingAProviderRecordsAPassingCheck() {
        // Through the resource, not the createProvider() helper: only the resource's create()
        // pings and records the check — the helper calls the registry directly to let other
        // tests set up a provider before choosing what the WireMock stub returns.
        server.stubFor(get("/models").willReturn(aResponse().withStatus(200).withBody("{}")));
        LlmProviderInput in = new LlmProviderInput("TEST-llm-" + UUID.randomUUID(), "openai",
                server.baseUrl(), "TEST-KEY", "TEST-MODEL", 0.0, null, true, false);

        Response response = resource.create(in);
        LlmProviderView created = (LlmProviderView) response.getEntity();

        LlmProviderView reread = registry.get(UUID.fromString(created.id())).orElseThrow();
        assertTrue(reread.lastCheckOk(), "a successful create already re-validated the key");
        assertNull(reread.lastCheckError());
    }

    /** The negative case that protects the decision: silence must not read as success. */
    @Test
    void anUpdateWithNoNewApiKeyDoesNotClearARejectedCredential() {
        server.stubFor(get("/models").willReturn(aResponse().withStatus(401)));
        String id = createProvider();
        resource.check(id); // records the rejection

        LlmProviderView view = registry.get(UUID.fromString(id)).orElseThrow();
        // No apiKey -> keeps the stored one; only the name changes.
        resource.update(id, new LlmProviderInput(view.name() + "-renamed", view.type(), view.baseUrl(),
                null, view.model(), view.temperature(), view.maxTokens(), view.enabled(), view.isDefault()));

        LlmProviderView reread = registry.get(UUID.fromString(id)).orElseThrow();
        assertFalse(reread.lastCheckOk(), "an update that never touched the key must not clear its rejection");
    }

    /** The positive counterpart: a genuine re-validation does clear the rejection. */
    @Test
    void anUpdateWithANewApiKeyClearsARejectedCredential() {
        server.stubFor(get("/models").willReturn(aResponse().withStatus(401)));
        String id = createProvider();
        resource.check(id); // records the rejection

        server.stubFor(get("/models").willReturn(aResponse().withStatus(200).withBody("{}")));
        LlmProviderView view = registry.get(UUID.fromString(id)).orElseThrow();
        resource.update(id, new LlmProviderInput(view.name(), view.type(), view.baseUrl(),
                "TEST-NEW-KEY", view.model(), view.temperature(), view.maxTokens(), view.enabled(), view.isDefault()));

        LlmProviderView reread = registry.get(UUID.fromString(id)).orElseThrow();
        assertTrue(reread.lastCheckOk(), "a genuine re-validation must clear the prior rejection");
        assertNull(reread.lastCheckError());
    }
}
