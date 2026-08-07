package dev.codespire.orchestrator.llm;

import dev.codespire.contract.review.ModelUsage;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.test.junit.QuarkusTest;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.containsString;
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

    /**
     * The gap that let hole #1 through. requireNonNegative rejected null and ACCEPTED zero, while the
     * UI turned a blank field into zero, so "no price entered" arrived as a valid free model. Zero is
     * now only legal as a deliberate UNMETERED assertion.
     */
    @Test
    void aZeroRateOnAMeteredModelIsRejectedWithAnActionableMessage() {
        given().contentType(ContentType.JSON)
                .body("""
                      {"type":"openai","name":"TEST-ZERO","label":"TEST zero","pricingMode":"METERED",
                       "rates":{"INPUT":0,"OUTPUT":400000}}
                      """)
                .when().post("/api/llm-models")
                .then().statusCode(400)
                .body(containsString("UNMETERED"));
    }

    @Test
    void anUnmeteredModelCarryingRatesIsRejected() {
        given().contentType(ContentType.JSON)
                .body("""
                      {"type":"openai","name":"TEST-CONFUSED","label":"TEST","pricingMode":"UNMETERED",
                       "rates":{"INPUT":200000}}
                      """)
                .when().post("/api/llm-models")
                .then().statusCode(400);
    }

    @Test
    void anUnknownPricingModeIsRejectedSoUnknownCannotBeChosen() {
        given().contentType(ContentType.JSON)
                .body("""
                      {"type":"openai","name":"TEST-UNKNOWN","label":"TEST","pricingMode":"UNKNOWN",
                       "rates":{}}
                      """)
                .when().post("/api/llm-models")
                .then().statusCode(400);
    }

    /**
     * Both of LlmModelPricingValidator.parseRateType's rejections, asserted on the RESPONSE BODY and
     * not merely the 400. Every other rates map in this module's tests uses one of the five legal token
     * types, so neither branch had any coverage at all — and a status-only assertion would not have
     * distinguished them, since both answer 400 for entirely different reasons.
     *
     * <p>The body matters here beyond telling the two apart: a BadRequestException built from a bare
     * string sets the exception's message and NOT the entity, which discards the sentence the operator
     * needs (see techdebt/spire-orchestrator/3-3). Asserting the text is what pins the message as
     * delivered rather than merely written.
     */
    @Test
    void anUnrecognizedRateKeyIsRejectedAndNamesTheOffendingKey() {
        given().contentType(ContentType.JSON)
                .body("""
                      {"type":"openai","name":"TEST-BOGUS-KEY","label":"TEST","pricingMode":"METERED",
                       "rates":{"INPUT":200000,"OUTPUT":400000,"BOGUS":100}}
                      """)
                .when().post("/api/llm-models")
                .then().statusCode(400)
                .body(containsString("Unknown token type"))
                // Naming the rejected key is the actionable half: a rates map may carry several, and
                // "one of these is wrong" leaves the operator to guess which.
                .body(containsString("BOGUS"));
    }

    /**
     * TOTAL is a legal {@code TokenType} but never a rate key: it exists for an unreconciled call whose
     * per-type breakdown did not sum to the vendor's own total, so it names a whole call rather than one
     * priceable dimension of one. Rejecting it at the boundary is what stops the ledger's
     * "TOTAL on a metered model cannot be priced" invariant from being defeated at configuration time.
     */
    @Test
    void totalIsRejectedAsARateKeyBecauseItPricesNoSingleDimension() {
        given().contentType(ContentType.JSON)
                .body("""
                      {"type":"openai","name":"TEST-TOTAL-RATE","label":"TEST","pricingMode":"METERED",
                       "rates":{"INPUT":200000,"OUTPUT":400000,"TOTAL":1}}
                      """)
                .when().post("/api/llm-models")
                .then().statusCode(400)
                .body(containsString("TOTAL has no per-call rate"));
    }

    @Test
    void aMeteredModelMissingTheOutputRateIsRejected() {
        given().contentType(ContentType.JSON)
                .body("""
                      {"type":"openai","name":"TEST-PARTIAL","label":"TEST","pricingMode":"METERED",
                       "rates":{"INPUT":200000}}
                      """)
                .when().post("/api/llm-models")
                .then().statusCode(400).body(containsString("OUTPUT"));
    }

    @Test
    void aMeteredModelWithBothMandatoryRatesIsCreated() {
        given().contentType(ContentType.JSON)
                .body("""
                      {"type":"openai","name":"TEST-GOOD","label":"TEST good","pricingMode":"METERED",
                       "rates":{"INPUT":200000,"OUTPUT":400000}}
                      """)
                .when().post("/api/llm-models")
                .then().statusCode(201).body("rates.INPUT", equalTo(200000));
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

    /**
     * Before this task the registry's in-use guard threw IllegalStateException straight past the
     * resource, so the API answered 500 for a condition the caller can act on. It must answer 409 with
     * the message intact — the message already names the model and how many providers use it.
     */
    @Test
    void deletingAModelInUseByAProviderAnswersConflictNotServerError() {
        registry.create(new LlmModelInput("openai", "TEST-IN-USE", "TEST in use",
                "METERED", Map.of("INPUT", 200_000L, "OUTPUT", 400_000L),
                null, null, null, Map.of(), true));
        String modelId = registry.list().stream()
                .filter(m -> m.name().equals("TEST-IN-USE")).findFirst().orElseThrow().id();
        providers.create(new LlmProviderInput("TEST provider", "openai", "http://example.invalid",
                "sk-test", "TEST-IN-USE", 0.2, null, true, true));

        given().when().delete("/api/llm-models/" + modelId)
                .then().statusCode(409).body(containsString("TEST-IN-USE"));
    }

    /**
     * Companion to the delete test above, for the rename guard update() gained after it. Before
     * this fix, update() had no catch for the registry's in-use IllegalStateException (unlike
     * delete(), which already had one) — the guard's message never reached the client, and a
     * caller saw a bodiless 500 instead of the actionable refusal the registry produced. Asserting
     * the body, not just the status, is the point: a status-only check cannot tell a mapped 409
     * from an unmapped one wearing the right number by coincidence.
     */
    @Test
    void renamingAModelInUseByAProviderAnswersConflictNotServerError() {
        registry.create(new LlmModelInput("openai", "TEST-RENAME-CONFLICT", "TEST rename conflict",
                "METERED", Map.of("INPUT", 200_000L, "OUTPUT", 400_000L),
                null, null, null, Map.of(), true));
        String modelId = registry.list().stream()
                .filter(m -> m.name().equals("TEST-RENAME-CONFLICT")).findFirst().orElseThrow().id();
        providers.create(new LlmProviderInput("TEST-RENAME-CONFLICT-PROVIDER", "openai",
                "http://example.invalid", "sk-test", "TEST-RENAME-CONFLICT", 0.2, null, true, true));

        given().contentType(ContentType.JSON)
                .body(Map.of("type", "openai", "name", "TEST-RENAME-CONFLICT-NEW",
                        "label", "TEST rename conflict", "pricingMode", "METERED",
                        "rates", Map.of("INPUT", 200_000, "OUTPUT", 400_000)))
                .when().put("/api/llm-models/" + modelId)
                .then().statusCode(409)
                .body(containsString("TEST-RENAME-CONFLICT"))
                .body(containsString("Point them at another model first"));
    }
}
