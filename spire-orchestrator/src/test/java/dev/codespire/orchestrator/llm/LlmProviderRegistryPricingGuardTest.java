package dev.codespire.orchestrator.llm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The REST layer's own guard (LlmProviderModelGuardTest) is enforced at the one caller that
 * happens to exist, not at the invariant's real boundary. This asserts the registry itself
 * refuses an unpriceable model — the check a seeder, an import, or a test reaching {@link
 * LlmProviderRegistry} directly cannot route around.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class LlmProviderRegistryPricingGuardTest {

    @Inject
    LlmProviderRegistry registry;

    @Inject
    LlmModelRegistry models;

    @Test
    void creatingAProviderNamingAnUncataloguedModelIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> registry.create(providerNaming("TEST-GUARD-UNCATALOGUED")));

        assertTrue(refused.getMessage().contains("catalog"),
                "the refusal must name the fix, not merely refuse: " + refused.getMessage());
    }

    /** The bypass this task closes: the REST layer already refused this, the registry did not. */
    @Test
    void updatingAProviderOntoAnUncataloguedModelIsRefused() {
        models.create(new LlmModelInput("openai", "TEST-GUARD-UPDATE-SOURCE", "TEST-GUARD-UPDATE-SOURCE",
                "UNMETERED", Map.of(), null, null, null, null, true));
        LlmProviderView created = registry.create(providerNaming("TEST-GUARD-UPDATE-SOURCE"));

        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> registry.update(UUID.fromString(created.id()),
                        providerNaming("TEST-GUARD-UPDATE-TARGET-UNCATALOGUED")));

        assertTrue(refused.getMessage().contains("catalog"),
                "the refusal must name the fix, not merely refuse: " + refused.getMessage());
    }

    private LlmProviderInput providerNaming(String model) {
        return new LlmProviderInput("provider-" + model, "openai", "http://localhost", "sk-test", model,
                0.2, null, true, false);
    }
}
