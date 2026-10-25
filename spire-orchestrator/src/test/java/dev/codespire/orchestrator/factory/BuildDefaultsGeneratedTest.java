package dev.codespire.orchestrator.factory;

import dev.codespire.orchestrator.llm.LlmModelInput;
import dev.codespire.orchestrator.llm.LlmModelRegistry;
import dev.codespire.orchestrator.provider.ProviderInput;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.repository.RepositoryInput;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.work.GeneratedPreparationProfile;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The preparation setting with the generated path switched on (M4 slice 3, §6.1). Nothing reads the
 * setting yet; these prove only that it is stored, kept, and offered.
 */
@QuarkusTest
@TestProfile(GeneratedPreparationProfile.class)
@TestSecurity(user = "TEST-build-admin", roles = "spire-admin")
class BuildDefaultsGeneratedTest {

    @Inject BuildDefaults defaults;
    @Inject RepositoryRegistry repositories;
    @Inject ProviderRegistry providers;
    @Inject LlmModelRegistry models;
    @Inject DataSource dataSource;

    UUID repository;
    String model;

    @BeforeEach
    void setup() throws SQLException {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        UUID account = UUID.fromString(providers.create(new ProviderInput("TEST-generated-" + suffix, "github",
                "https://github.example.invalid", "bearer", null, "TEST-token", "TEST-bot", true,
                List.of(), "TEST-bot", null, "REVIEWER")).id());
        repository = repositories.create(new RepositoryInput("github", "https://github.example.invalid",
                "TEST-generated", "TEST-repo-" + suffix, true, account, null)).id();
        model = "TEST-model-" + suffix;
        models.create(new LlmModelInput("openai", model, "TEST model", "METERED",
                Map.of("INPUT", 100L, "OUTPUT", 200L), "max_tokens", true, null, Map.of(), true,
                List.of("CACHED_INPUT", "CACHE_WRITE", "REASONING")));
        // A catalogue another suite recorded could name other models and refuse this one; the save under
        // test is about the preparation, not the harness's list.
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM harness_catalogue");
        }
    }

    private BuildDefaults.Input preparing(String preparation) {
        return new BuildDefaults.Input(defaults.get(repository).revision(), "main", "codex", model, null, null, List.of(), null,
                preparation);
    }

    @Test
    void savesGenerated() {
        BuildDefaults.Defaults saved = defaults.save(repository, preparing("generated"), "TEST-operator");

        assertEquals("generated", saved.preparation());
        assertEquals(1, saved.revision());
        assertEquals("generated", defaults.get(repository).preparation());
    }

    /** A form that does not show the choice sends none, and must not switch the repository back. */
    @Test
    void anOmittedPreparationKeepsTheStoredOne() {
        defaults.save(repository, preparing("generated"), "TEST-operator");

        BuildDefaults.Defaults saved = defaults.save(repository, preparing(null), "TEST-operator");

        assertEquals("generated", saved.preparation());
        assertEquals(2, saved.revision());
    }

    @Test
    void anUnknownPreparationIsStillRefused() {
        BuildDefaults.Input input = preparing("TEST-other");
        assertEquals("preparation_invalid", org.junit.jupiter.api.Assertions.assertThrows(BuildDefaults.Refused.class,
                () -> defaults.save(repository, input, "TEST-operator")).reason());
    }

    @Test
    void optionsOfferGeneratedOnlyWhenOn() {
        given().get("/api/repositories/" + repository + "/factory/build/options").then().statusCode(200)
                .body("preparations", is(List.of("composed", "generated")));
    }

    /** The screen saves over HTTP, so the field must survive the wire both ways. */
    @Test
    void theEndpointStoresGenerated() {
        given().contentType(io.restassured.http.ContentType.JSON)
                .body(Map.of("expectedRevision", 0, "baseBranch", "main", "harness", "codex", "model", model,
                        "preparation", "generated"))
                .put("/api/repositories/" + repository + "/factory/build").then().statusCode(200)
                .body("preparation", is("generated"));
    }
}
