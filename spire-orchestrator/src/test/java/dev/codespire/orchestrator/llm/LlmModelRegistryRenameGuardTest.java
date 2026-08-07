package dev.codespire.orchestrator.llm;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code llm_provider.model} is a bare string reference to {@code llm_model.name} — no foreign
 * key, only a UNIQUE constraint on the catalog side. {@link LlmModelRegistry#delete} already
 * refuses to remove a model a provider still names; this asserts {@code update} refuses to rename
 * one out from under its referencing provider the same way, while everything else about a
 * referenced model — including a no-op rename — stays editable.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class LlmModelRegistryRenameGuardTest {

    @Inject
    LlmModelRegistry models;

    @Inject
    LlmProviderRegistry providers;

    @Test
    void renamingAModelInUseByAProviderIsRefused() {
        LlmModelView created = models.create(meteredModel("TEST-RENAME-GUARD-INUSE"));
        providers.create(new LlmProviderInput("TEST-RENAME-GUARD-PROVIDER", "openai",
                "http://localhost", "sk-test", "TEST-RENAME-GUARD-INUSE", 0.2, null, true, false));

        // The EXACT type, not its IllegalStateException supertype: LlmModelRegistry wraps every
        // SQLException as a plain IllegalStateException too, so asserting the supertype accepts a
        // broken database as proof the guard fired. A mutation reverting the guard's type stayed green.
        ModelInUseException refused = assertThrows(ModelInUseException.class,
                () -> models.update(UUID.fromString(created.id()),
                        meteredModel("TEST-RENAME-GUARD-INUSE-NEW")));

        assertTrue(refused.getMessage().contains("TEST-RENAME-GUARD-INUSE")
                        && refused.getMessage().contains("Point them at another model first"),
                "the refusal must name the model and the fix: " + refused.getMessage());
    }

    @Test
    void renamingAnUnreferencedModelSucceeds() {
        LlmModelView created = models.create(meteredModel("TEST-RENAME-GUARD-FREE"));

        LlmModelView renamed = models.update(UUID.fromString(created.id()),
                meteredModel("TEST-RENAME-GUARD-FREE-RENAMED")).orElseThrow();

        assertEquals("TEST-RENAME-GUARD-FREE-RENAMED", renamed.name());
    }

    @Test
    void otherFieldsRemainEditableWhileAModelIsReferenced() {
        LlmModelView created = models.create(meteredModel("TEST-RENAME-GUARD-EDITABLE"));
        providers.create(new LlmProviderInput("TEST-RENAME-GUARD-EDITABLE-PROVIDER", "openai",
                "http://localhost", "sk-test", "TEST-RENAME-GUARD-EDITABLE", 0.2, null, true, false));

        // Same name (no rename), but label/rates/outputTokenParam all change — none of that is the
        // reference, so none of it should be blocked by the guard.
        LlmModelView updated = models.update(UUID.fromString(created.id()),
                new LlmModelInput("openai", "TEST-RENAME-GUARD-EDITABLE", "TEST relabeled", "METERED",
                        Map.of("INPUT", 300_000L, "OUTPUT", 600_000L),
                        "MAX_COMPLETION_TOKENS", null, null, Map.of(), true)).orElseThrow();

        assertEquals("TEST relabeled", updated.label());
        assertEquals(300_000L, updated.rates().get("INPUT"));
        assertEquals("MAX_COMPLETION_TOKENS", updated.outputTokenParam());
    }

    private static LlmModelInput meteredModel(String name) {
        return new LlmModelInput("openai", name, name, "METERED",
                Map.of("INPUT", 200_000L, "OUTPUT", 400_000L), null, null, null, null, true);
    }
}
