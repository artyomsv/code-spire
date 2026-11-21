package dev.codespire.orchestrator.llm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;

/**
 * The Settings dropdown is a courtesy; this is the control. A default model that is not in the catalog cannot be
 * priced, so every review on it would land in the ledger as UNKNOWN — the exact state the accounting rework exists to
 * make impossible to configure. The model is chosen when a provider becomes the default, so that is where it is
 * refused.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class LlmProviderModelGuardTest {

    @Inject LlmProviderRegistry providers;
    @Inject DataSource dataSource;

    private UUID provider;

    @BeforeEach
    void aProvider() {
        provider = UUID.fromString(providers.create(new LlmProviderInput("TEST-guard-" + UUID.randomUUID(), "openai",
                "https://api.example.invalid", "TEST-KEY", null, null, true, false, null)).id());
    }

    @AfterEach
    void removeIt() throws SQLException {
        providers.delete(provider);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM app_setting WHERE key=?")) {
            ps.setString(1, LlmDefaultModel.KEY);
            ps.executeUpdate();
        }
    }

    @Test
    void aDefaultNamingAnUncataloguedModelIsRejected() {
        given().contentType(ContentType.JSON).body(Map.of("model", "TEST-NOT-IN-CATALOG"))
                .when().put("/api/llm-providers/" + provider + "/default")
                .then().statusCode(400).body(containsString("catalog"));
    }

    @Test
    void aDefaultNamingACataloguedPriceableModelIsAccepted() {
        given().contentType(ContentType.JSON)
                .body("""
                      {"type":"openai","name":"TEST-GUARD-MODEL","label":"TEST guard",
                       "pricingMode":"METERED","rates":{"INPUT":2,"OUTPUT":3}}
                      """)
                .when().post("/api/llm-models").then().statusCode(201);

        given().contentType(ContentType.JSON).body(Map.of("model", "TEST-GUARD-MODEL"))
                .when().put("/api/llm-providers/" + provider + "/default")
                .then().statusCode(200);
    }
}
