package dev.codespire.orchestrator.llm.catalog;

import dev.codespire.orchestrator.llm.LlmModelInput;
import dev.codespire.orchestrator.llm.LlmModelRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/** GET /api/llm-catalog and POST /api/llm-catalog/refresh, on the %test catalog (url none). */
@QuarkusTest
class LlmCatalogResourceTest {

    @Inject
    LlmModelRegistry registry;

    @AfterEach
    void removeTheManualModel() {
        registry.list().stream().filter(m -> m.id() != null && m.name().equals("TEST-cat-o"))
                .forEach(m -> registry.delete(UUID.fromString(m.id())));
    }

    @Test
    @TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
    void getListsShadowedRows() {
        registry.create(new LlmModelInput("openai", "TEST-cat-o", "TEST manual", "METERED",
                Map.of("INPUT", 5L, "OUTPUT", 6L), null, null, null, Map.of(), true, List.of()));

        given().when().get("/api/llm-catalog").then().statusCode(200)
                .body("source", equalTo("bundled"))
                .body("url", nullValue())
                .body("rows.find { it.model.modelId == 'TEST-cat-o' }.shadowed", equalTo(true))
                .body("rows.find { it.model.modelId == 'TEST-cat-a' }.shadowed", equalTo(false))
                .body("rows.find { it.model.modelId == 'TEST-cat-a' }.model.pricing.observedAt", equalTo("2026-11-20"))
                .body("rows.find { it.model.modelId == 'TEST-cat-retired' }.model.lifecycle.status",
                        equalTo("RETIRED"));
    }

    @Test
    @TestSecurity(user = "test-viewer", roles = "spire-viewer")
    void refreshIsAdminOnly() {
        given().when().post("/api/llm-catalog/refresh").then().statusCode(403);
        given().when().get("/api/llm-catalog").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
    void refreshWithRemoteLoadingOffSaysSo() {
        given().when().post("/api/llm-catalog/refresh").then().statusCode(200)
                .body("outcome", equalTo("DISABLED"));
    }
}
