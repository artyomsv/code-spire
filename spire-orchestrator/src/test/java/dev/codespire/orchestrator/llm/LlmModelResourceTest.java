package dev.codespire.orchestrator.llm;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static io.restassured.RestAssured.when;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The /api/llm-models catalog: CRUD + validation, and the pricing lookup used to
 * cost a review's token usage (roadmap 11).
 */
@QuarkusTest
class LlmModelResourceTest {

    @Inject
    LlmModelRegistry registry;

    @BeforeEach
    void clean() {
        registry.list().forEach(m -> registry.delete(UUID.fromString(m.id())));
    }

    private static Map<String, Object> gpt4o() {
        return Map.of("type", "openai", "name", "gpt-4o", "label", "GPT-4o",
                "inputPriceMillicentsPerMillion", 250_000, // $2.50 / 1M
                "outputPriceMillicentsPerMillion", 1_000_000); // $10.00 / 1M
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
                .body(Map.of("type", "anthropic", "name", "claude", "label", "C",
                        "inputPriceMillicentsPerMillion", 1, "outputPriceMillicentsPerMillion", 1))
                .when().post("/api/llm-models").then().statusCode(400);
    }

    @Test
    void rejectsAMissingName() {
        given().contentType("application/json")
                .body(Map.of("type", "openai", "label", "X",
                        "inputPriceMillicentsPerMillion", 1, "outputPriceMillicentsPerMillion", 1))
                .when().post("/api/llm-models").then().statusCode(400);
    }

    @Test
    void rejectsANegativePrice() {
        given().contentType("application/json")
                .body(Map.of("type", "openai", "name", "m", "label", "M",
                        "inputPriceMillicentsPerMillion", -1, "outputPriceMillicentsPerMillion", 1))
                .when().post("/api/llm-models").then().statusCode(400);
    }

    @Test
    void pricesAReviewFromTokenUsage() {
        registry.create(new LlmModelInput("openai", "gpt-4o", "GPT-4o", 250_000L, 1_000_000L, true));
        // (10000 * 250000 + 2000 * 1000000) / 1_000_000 = 4500 millicents = $0.045
        assertEquals(4500L, registry.costMillicents("gpt-4o", 10_000, 2_000));
    }

    @Test
    void uncataloguedModelCostsZero() {
        assertEquals(0L, registry.costMillicents("not-registered", 1_000, 1_000));
    }
}
