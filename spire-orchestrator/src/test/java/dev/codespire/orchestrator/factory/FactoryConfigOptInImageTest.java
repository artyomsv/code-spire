package dev.codespire.orchestrator.factory;

import dev.codespire.contract.work.PayWith;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.repository.RepositoryInput;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.repository.ReviewHarness;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Claude Code's agent image is opt-in (harness reviews 3b, Task 3).
 *
 * <p>On the real {@code application.yml} with {@code SPIRE_FACTORY_AGENT_IMAGE_CLAUDE_CODE} unset: the orchestrator
 * starts (an empty map value would fail startup, so the unset value is {@code none}), and {@code claude_code} is in no
 * list and refused as unconfigured, because every reader asks {@link FactoryConfig#configuredAgentImages()}. The
 * configured case is {@link FactoryConfigOptInImageConfiguredTest}.
 */
@QuarkusTest
@TestSecurity(user = "TEST-opt-in-admin", roles = "spire-admin")
class FactoryConfigOptInImageTest {

    static final String CLAUDE = "claude_code";
    static final String CODEX = "codex";

    @Inject FactoryConfig config;
    @Inject BuildDefaults defaults;
    @Inject RepositoryRegistry repositories;

    static RepositoryInput reviewingWith(String harness) {
        return new RepositoryInput("github", "https://github.com", "TEST-ws", "TEST-opt-in-" + UUID.randomUUID(), true,
                null, null, true, null, new ReviewHarness(harness, "TEST-model", null, PayWith.API_KEY));
    }

    static String buildDefaultsRefusal(BuildDefaults defaults, String harness) {
        BuildDefaults.Input input = new BuildDefaults.Input(1, "main", harness, "TEST-model", null, PayWith.API_KEY);
        return assertThrows(BuildDefaults.Refused.class,
                () -> defaults.save(UUID.randomUUID(), input, "TEST-operator")).reason();
    }

    @Test
    void theUnsetImageIsReadAsNoneAndLeftOutOfTheConfiguredImages() {
        assertEquals(FactoryConfig.NOT_CONFIGURED, config.agentImage().get(CLAUDE),
                "the sentinel is what startup reads when the variable is unset");
        assertFalse(config.configuredAgentImages().containsKey(CLAUDE), config.configuredAgentImages().toString());
        assertTrue(config.configuredAgentImages().containsKey(CODEX), "Codex keeps its default image");
    }

    @Test
    void theBuildOptionsDoNotOfferIt() {
        given().get("/api/repositories/" + UUID.randomUUID() + "/factory/build/options").then().statusCode(200)
                .body("harnesses", hasItem(CODEX), "harnesses", not(hasItem(CLAUDE)));
    }

    @Test
    void theItemPreparationOptionsDoNotOfferIt() {
        given().get("/api/work-items/TEST-opt-in/preparation/options").then().statusCode(200)
                .body("harnesses", hasItem(CODEX), "harnesses", not(hasItem(CLAUDE)));
    }

    @Test
    void buildDefaultsRefuseItAsUnconfigured() {
        assertEquals("harness_unconfigured", buildDefaultsRefusal(defaults, CLAUDE));
    }

    @Test
    void reviewWithRefusesItAsUnconfigured() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> repositories.update(UUID.randomUUID(), 1, reviewingWith(CLAUDE)));

        assertTrue(refused.getMessage().contains("unknown harness " + CLAUDE), refused.getMessage());
    }

    /** The guard's twin: Codex, configured by default, passes every check and reaches the write. */
    @Test
    void reviewWithCodexIsNotRefusedAsUnconfigured() {
        RuntimeException outcome = assertThrows(RuntimeException.class,
                () -> repositories.update(UUID.randomUUID(), 1, reviewingWith(CODEX)));

        assertInstanceOf(ProviderRegistry.AccountConflict.class, outcome, outcome.getMessage());
    }

    /** Pure: the filter drops exactly the sentinel, whatever else the map holds. */
    @Test
    void onlyTheSentinelIsDropped() {
        FactoryConfig mapped = new FactoryConfig() {
            @Override public java.util.Map<String, String> agentImage() {
                return java.util.Map.of(CODEX, "TEST-codex-image", CLAUDE, FactoryConfig.NOT_CONFIGURED);
            }
            @Override public long wallClockSeconds() { return 1800; }
            @Override public long reviewWallClockSeconds() { return 900; }
            @Override public Fix fix() { throw new AssertionError("not read here"); }
        };

        assertEquals(List.of(CODEX), mapped.configuredAgentImages().keySet().stream().sorted().toList());
    }
}
