package dev.codespire.orchestrator.factory;

import dev.codespire.contract.review.TokenType;
import dev.codespire.contract.work.PayWith;
import dev.codespire.orchestrator.llm.LlmModelPricer;
import dev.codespire.orchestrator.llm.LlmModelRegistry;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.repository.RepositoryInput;
import dev.codespire.orchestrator.repository.RepositoryRegistry;
import dev.codespire.orchestrator.repository.ReviewHarness;
import dev.codespire.orchestrator.work.WorkTestDoors;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A subscription for Claude Code is refused at every door it can arrive by (harness reviews 3b, Review Focus 2).
 *
 * <p>Claude Code never pays with a Claude subscription: Anthropic's terms forbid a third-party product to route
 * requests through one or to hold claude.ai tokens. Nothing here signs in to, stores or runs one; each test hands one
 * door a subscription and asserts that door alone refuses it, with the collaborators behind the door failing the test
 * if they are reached. Each door's Codex twin is the regression guard: it does not refuse for this reason.
 *
 * <p>This class holds the review-setting, build-defaults and options doors; the sign-in and pool doors are in
 * {@link SubscriptionNotAllowedSignInTest}, the funding door in {@link SubscriptionNotAllowedFundingTest}. A Quarkus
 * test because the review setting writes in the database; the other doors are built by hand with fakes, because this
 * deployment's test profile has no Claude Code agent image.
 */
@QuarkusTest
class SubscriptionNotAllowedTest {

    private static final String CLAUDE = "claude_code";
    private static final String CODEX = "codex";
    private static final String NOT_ALLOWED = "subscription_not_allowed";

    @Inject RepositoryRegistry repositories;

    private final List<String> reached = new ArrayList<>();

    // --- the review setting ------------------------------------------------------------------

    private static RepositoryInput reviewingWith(String harness, String payWith) {
        return new RepositoryInput("github", "https://github.com", "TEST-ws", "TEST-3b-" + UUID.randomUUID(), true,
                null, null, true, null, new ReviewHarness(harness, "TEST-model", null, payWith));
    }

    @Test
    void aReviewSettingWithAClaudeSubscriptionIsRefused() {
        IllegalArgumentException refused = assertThrows(IllegalArgumentException.class,
                () -> repositories.update(UUID.randomUUID(), 1, reviewingWith(CLAUDE, PayWith.SUBSCRIPTION)));

        assertTrue(refused.getMessage().contains(NOT_ALLOWED), refused.getMessage());
    }

    /** Past every check: the update then finds no such repository, which is the write, not a refusal. */
    @Test
    void aReviewSettingWithACodexSubscriptionIsNotRefusedForIt() {
        RuntimeException outcome = assertThrows(RuntimeException.class,
                () -> repositories.update(UUID.randomUUID(), 1, reviewingWith(CODEX, PayWith.SUBSCRIPTION)));

        assertInstanceOf(ProviderRegistry.AccountConflict.class, outcome, outcome.getMessage());
    }

    // --- build defaults ------------------------------------------------------------------------

    /** Both harnesses configured, so neither is refused as unconfigured before the guard under test. */
    static FactoryConfig bothHarnesses() {
        return new FactoryConfig() {
            @Override public Map<String, String> agentImage() { return Map.of(CODEX, "TEST-codex-image", CLAUDE, "TEST-claude-image"); }
            @Override public long wallClockSeconds() { return 1800; }
            @Override public long reviewWallClockSeconds() { return 900; }
            @Override public Fix fix() { throw new AssertionError("no door here reads the fix configuration"); }
        };
    }

    private BuildDefaults buildDefaults() {
        BuildDefaults defaults = new BuildDefaults();
        defaults.config = bothHarnesses();
        defaults.catalogues = new HarnessCatalogues() {
            @Override public Optional<String> refusal(String harness, String model, String effort) {
                reached.add("catalogue");
                return Optional.empty();
            }
        };
        defaults.pool = new HarnessCredentialPool() {
            @Override public boolean hasSubscription(String harness) {
                reached.add("seat");
                return false;
            }
        };
        defaults.models = new LlmModelRegistry() {
            @Override public List<dev.codespire.orchestrator.llm.LlmModelView> list() { throw new AssertionError("a subscription is not priced"); }
        };
        defaults.pricer = new LlmModelPricer() {
            @Override public List<TokenType> unpricedTypes(String model, String harness) { throw new AssertionError("a subscription is not priced"); }
        };
        return defaults;
    }

    private String buildDefaultsRefusal(String harness) {
        BuildDefaults.Input input = new BuildDefaults.Input(1, "main", harness, "TEST-model", null, PayWith.SUBSCRIPTION);
        return assertThrows(BuildDefaults.Refused.class,
                () -> buildDefaults().save(UUID.randomUUID(), input, "TEST-operator")).reason();
    }

    @Test
    void buildDefaultsWithAClaudeSubscriptionAreRefused() {
        assertEquals(NOT_ALLOWED, buildDefaultsRefusal(CLAUDE));
        assertEquals(List.of(), reached, "refused before the catalogue and the seat are asked");
    }

    @Test
    void buildDefaultsWithACodexSubscriptionAreNotRefusedForIt() {
        assertEquals("subscription_not_signed_in", buildDefaultsRefusal(CODEX));
    }

    // --- what the screens are offered --------------------------------------------------------------------

    @Test
    void optionsNameTheAllowedHarnesses() {
        RepositoryBuildResource resource = new RepositoryBuildResource();
        resource.config = bothHarnesses();
        resource.catalogues = new HarnessCatalogues() {
            @Override public Optional<Catalogue> get(String harness) { return Optional.empty(); }
        };

        RepositoryBuildResource.Options build = resource.options();
        assertEquals(List.of(CLAUDE, CODEX), build.harnesses(), "every configured harness is still listed");
        assertEquals(List.of(CODEX), build.subscriptionHarnesses());
        assertEquals(List.of(CODEX), build.buildHarnesses());

        dev.codespire.orchestrator.work.WorkPreparationResource.Options preparation =
                WorkTestDoors.preparationOptions(bothHarnesses());
        assertEquals(List.of(CODEX), preparation.subscriptionHarnesses());
        assertEquals(List.of(CODEX), preparation.buildHarnesses());
    }
}
