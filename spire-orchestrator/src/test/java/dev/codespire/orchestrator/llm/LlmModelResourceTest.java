package dev.codespire.orchestrator.llm;

import dev.codespire.contract.review.ModelUsage;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The /api/llm-models catalog: CRUD + validation, and the pricing lookup used to
 * cost a review's token usage (roadmap 11).
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class LlmModelResourceTest {

    @Inject
    LlmModelRegistry registry;

    @Inject
    LlmProviderRegistry providers;

    @BeforeEach
    void clean() {
        // Providers first: the catalog's delete guard refuses to remove a model an llm_provider row
        // names, and every @QuarkusTest class in this module shares one application and one database.
        // A sibling class that leaves a provider behind — this class included, if a test fails between
        // creating a model and its provider — would otherwise make this method throw, and whether it
        // throws would depend on which class or test ran first: a green suite that depends on order.
        providers.list().forEach(p -> providers.delete(UUID.fromString(p.id())));
        registry.list().forEach(m -> registry.delete(UUID.fromString(m.id())));
    }

    private static Map<String, Object> gpt4o() {
        return Map.of("type", "openai", "name", "gpt-4o", "label", "GPT-4o",
                "pricingMode", "METERED",
                "rates", Map.of("INPUT", 250_000, "OUTPUT", 1_000_000)); // $2.50 / $10.00 per 1M
    }

    @Test
    void createsAndLists() {
        given().contentType("application/json").body(gpt4o())
                .when().post("/api/llm-models")
                .then().statusCode(201).body("name", equalTo("gpt-4o"));
        when().get("/api/llm-models").then().statusCode(200).body("[0].label", equalTo("GPT-4o"));
    }

    @Test
    void rejectsAnUnsupportedType() {
        given().contentType("application/json")
                .body(Map.of("type", "cohere", "name", "command-r", "label", "C",
                        "pricingMode", "METERED", "rates", Map.of("INPUT", 1, "OUTPUT", 1)))
                .when().post("/api/llm-models").then().statusCode(400);
    }

    @Test
    void rejectsAMissingName() {
        given().contentType("application/json")
                .body(Map.of("type", "openai", "label", "X",
                        "pricingMode", "METERED", "rates", Map.of("INPUT", 1, "OUTPUT", 1)))
                .when().post("/api/llm-models").then().statusCode(400);
    }

    @Test
    void pricesAReviewFromTokenUsage() {
        registry.create(new LlmModelInput("openai", "gpt-4o", "GPT-4o", "METERED",
                Map.of("INPUT", 250_000L, "OUTPUT", 1_000_000L), "MAX_TOKENS", true, null, Map.of(), true));

        List<ChargeLine> lines = registry.priceCall("gpt-4o", ModelUsage.of("gpt-4o", 10_000, 2_000));

        assertEquals(2, lines.size());
        // (10000 * 250000 + 2000 * 1000000) / 1_000_000 = 4500 millicents = $0.045
        assertEquals(4500L, lines.stream().mapToLong(ChargeLine::costMillicents).sum());
    }

    /**
     * The regression this branch was built for. An uncatalogued model used to be priced at 0, which
     * froze forever as "this call was free". It must now be UNKNOWN, with a null cost that no sum can
     * absorb.
     */
    @Test
    void anUncataloguedModelIsUnknownNotFree() {
        List<ChargeLine> lines = registry.priceCall("TEST-NOT-REGISTERED",
                ModelUsage.of("TEST-NOT-REGISTERED", 1_000, 1_000));

        assertFalse(lines.isEmpty());
        assertTrue(lines.stream().allMatch(l -> l.mode() == PricingMode.UNKNOWN));
        assertTrue(lines.stream().allMatch(l -> l.costMillicents() == null));
    }

    @Test
    void roundTripsTheParameterProfileAndBrokersItByName() {
        registry.create(new LlmModelInput("openai", "o3", "OpenAI o3", "METERED",
                Map.of("INPUT", 2_000_000L, "OUTPUT", 8_000_000L),
                "MAX_COMPLETION_TOKENS", false, "medium", Map.of("service_tier", "flex"), true));

        var view = registry.list().stream().filter(m -> m.name().equals("o3")).findFirst().orElseThrow();
        assertEquals("MAX_COMPLETION_TOKENS", view.outputTokenParam());
        assertEquals(false, view.supportsTemperature());
        assertEquals("medium", view.reasoningEffort());
        assertEquals("flex", view.extraParams().get("service_tier"));

        var profile = registry.profileForName("o3").orElseThrow();
        assertEquals(dev.codespire.contract.llm.ModelParamProfile.OutputTokenParam.MAX_COMPLETION_TOKENS,
                profile.outputTokenParam());
        assertEquals(false, profile.supportsTemperature());
        assertEquals("medium", profile.reasoningEffort());

        // an uncatalogued model has no profile — caller falls back to the legacy dialect
        assertEquals(java.util.Optional.empty(), registry.profileForName("not-registered"));
    }

    /**
     * The hazard this ordering fixes. A sibling test class that leaves a provider behind used to make
     * clean() throw, so whether this class passed depended on which class ran first. Creating the
     * collision explicitly here means a revert of clean()'s provider-first ordering fails on purpose
     * rather than waiting for an unlucky class order in CI.
     */
    @Test
    void cleanSucceedsEvenWhenAProviderStillReferencesACataloguedModel() {
        registry.create(new LlmModelInput("openai", "TEST-CLEAN-COLLISION", "TEST clean collision",
                "METERED", Map.of("INPUT", 200_000L, "OUTPUT", 400_000L),
                null, null, null, Map.of(), true));
        providers.create(new LlmProviderInput("TEST provider", "openai", "http://example.invalid",
                "sk-test", "TEST-CLEAN-COLLISION", 0.2, null, true, true));

        assertDoesNotThrow(this::clean);
    }
}
