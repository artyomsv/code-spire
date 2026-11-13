package dev.codespire.orchestrator.factory;

import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The other half of {@link FactoryConfigOptInImageTest}: with {@code spire.factory.agent-image.claude_code} set to an
 * image, Claude Code is a configured harness, offered and not refused as unconfigured.
 *
 * <p>Its own class because a Quarkus test profile is per class; the override stands in for
 * {@code SPIRE_FACTORY_AGENT_IMAGE_CLAUDE_CODE}.
 */
@QuarkusTest
@TestProfile(FactoryConfigOptInImageConfiguredTest.ClaudeCodeImage.class)
@TestSecurity(user = "TEST-opt-in-admin", roles = "spire-admin")
class FactoryConfigOptInImageConfiguredTest {

    private static final String IMAGE = "TEST-claude-code-image";

    public static final class ClaudeCodeImage implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("spire.factory.agent-image." + FactoryConfigOptInImageTest.CLAUDE, IMAGE);
        }
    }

    @Inject FactoryConfig config;
    @Inject BuildDefaults defaults;
    @Inject RepositoryRegistry repositories;

    @Test
    void theSetImageIsConfigured() {
        assertEquals(IMAGE, config.configuredAgentImages().get(FactoryConfigOptInImageTest.CLAUDE));
    }

    @Test
    void theBuildOptionsOfferIt() {
        given().get("/api/repositories/" + UUID.randomUUID() + "/factory/build/options").then().statusCode(200)
                .body("harnesses", hasItem(FactoryConfigOptInImageTest.CLAUDE));
    }

    @Test
    void buildDefaultsDoNotRefuseItAsUnconfigured() {
        assertNotEquals("harness_unconfigured",
                FactoryConfigOptInImageTest.buildDefaultsRefusal(defaults, FactoryConfigOptInImageTest.CLAUDE));
    }

    /** Past every check: the update then finds no such repository, which is the write, not a refusal. */
    @Test
    void reviewWithAcceptsIt() {
        RuntimeException outcome = assertThrows(RuntimeException.class,
                () -> repositories.update(UUID.randomUUID(), 1,
                        FactoryConfigOptInImageTest.reviewingWith(FactoryConfigOptInImageTest.CLAUDE)));

        assertInstanceOf(ProviderRegistry.AccountConflict.class, outcome, outcome.getMessage());
    }
}
