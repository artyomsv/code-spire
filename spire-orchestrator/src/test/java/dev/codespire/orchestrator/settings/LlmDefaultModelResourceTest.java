package dev.codespire.orchestrator.settings;

import dev.codespire.orchestrator.llm.LlmDefaultModel;
import dev.codespire.orchestrator.llm.LlmModelInput;
import dev.codespire.orchestrator.llm.LlmModelRegistry;
import dev.codespire.orchestrator.llm.LlmProviderInput;
import dev.codespire.orchestrator.llm.LlmProviderRegistry;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * {@code /api/settings/llm-default-model}: the default review model for the current default provider. A bad choice is
 * 400 with its reason, a choice with no default provider is 409, and null clears.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class LlmDefaultModelResourceTest {

    private static final String PATH = "/api/settings/llm-default-model";

    @Inject LlmDefaultModel defaultModels;
    @Inject LlmProviderRegistry providers;
    @Inject LlmModelRegistry models;
    @Inject DataSource dataSource;

    private String openaiModel;
    private String anthropicModel;

    @BeforeEach
    void noProvidersNoSetting() throws SQLException {
        providers.list().forEach(p -> providers.delete(UUID.fromString(p.id())));
        execute("DELETE FROM app_setting WHERE key=?", LlmDefaultModel.KEY);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        openaiModel = catalogued("openai", "TEST-ldmr-openai-" + suffix);
        anthropicModel = catalogued("anthropic", "TEST-ldmr-anthropic-" + suffix);
    }

    @AfterEach
    void removeFixture() throws SQLException {
        providers.list().forEach(p -> providers.delete(UUID.fromString(p.id())));
        execute("DELETE FROM app_setting WHERE key=?", LlmDefaultModel.KEY);
        execute("DELETE FROM llm_model WHERE name LIKE ?", "TEST-ldmr-%");
    }

    @Test
    void getNamesTheDefaultProviderAndModel() {
        UUID provider = defaultOpenaiProvider();
        defaultModels.setDefaultProvider(provider, openaiModel);

        given().when().get(PATH).then().statusCode(200)
                .body("provider", equalTo(provider.toString()))
                .body("model", equalTo(openaiModel));
    }

    @Test
    void settingTheModelWithNoDefaultProviderIs409() {
        given().contentType(ContentType.JSON).body(Map.of("model", openaiModel))
                .when().put(PATH).then().statusCode(409);
    }

    @Test
    void putChangesTheModel() {
        defaultOpenaiProvider();
        String other = catalogued("openai", "TEST-ldmr-openai-other-" + UUID.randomUUID().toString().substring(0, 8));

        given().contentType(ContentType.JSON).body(Map.of("model", other))
                .when().put(PATH).then().statusCode(200).body("model", equalTo(other));
    }

    @Test
    void putNullClearsTheModel() {
        UUID provider = defaultOpenaiProvider();
        defaultModels.setDefaultProvider(provider, openaiModel);
        Map<String, Object> body = new HashMap<>();
        body.put("model", null);

        given().contentType(ContentType.JSON).body(body)
                .when().put(PATH).then().statusCode(200)
                .body("provider", equalTo(provider.toString()))
                .body("model", nullValue());
    }

    @Test
    void anUnknownModelIs400() {
        defaultOpenaiProvider();

        given().contentType(ContentType.JSON).body(Map.of("model", "TEST-ldmr-not-in-catalog"))
                .when().put(PATH).then().statusCode(400).body(containsString("unknown_model"));
    }

    @Test
    void aModelOfAnotherVendorIs400() {
        defaultOpenaiProvider();

        given().contentType(ContentType.JSON).body(Map.of("model", anthropicModel))
                .when().put(PATH).then().statusCode(400).body(containsString("model_not_for_provider"));
    }

    /** Known and of the right vendor, but with no rates (as V30 left legacy rows): only pricing can refuse it. */
    @Test
    void anUnpriceableModelIs400() throws SQLException {
        defaultOpenaiProvider();
        String rateless = "TEST-ldmr-rateless-" + UUID.randomUUID().toString().substring(0, 8);
        execute("INSERT INTO llm_model (id, type, name, label, pricing_mode) VALUES (?, 'openai', ?, ?, 'METERED')",
                UUID.randomUUID(), rateless, rateless);

        given().contentType(ContentType.JSON).body(Map.of("model", rateless))
                .when().put(PATH).then().statusCode(400).body(containsString("model_not_priceable"));
    }

    // ---- fixture -------------------------------------------------------------

    /** The first provider, so the default one. */
    private UUID defaultOpenaiProvider() {
        return UUID.fromString(providers.create(new LlmProviderInput("TEST-ldmr-openai", "openai",
                "https://llm.example.invalid", "TEST-key", null, null, true, false, null)).id());
    }

    private String catalogued(String type, String name) {
        models.create(new LlmModelInput(type, name, name, "UNMETERED", Map.of(),
                null, null, null, null, true, List.of()));
        return name;
    }

    private void execute(String sql, Object... parameters) throws SQLException {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int i = 0; i < parameters.length; i++) {
                ps.setObject(i + 1, parameters[i]);
            }
            ps.executeUpdate();
        }
    }
}
