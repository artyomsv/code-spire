package dev.codespire.orchestrator.repository;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.QuarkusTestProfile;
import io.quarkus.test.junit.TestProfile;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;

/**
 * A new "Review with" harness choice names a model the harness runs and a level that model offers, as a build setup's
 * save checks; a choice saved before keeps saving when another setting changes. Claude Code is configured here (the
 * opt-in image profile), and offers the enabled anthropic models of the TEST catalog; codex offers what the fixture's
 * catalogue row lists.
 */
@QuarkusTest
@TestProfile(RepositoryReviewHarnessOfferTest.ClaudeCodeImage.class)
@TestSecurity(user = "TEST-admin", roles = {"spire-viewer", "spire-admin"})
class RepositoryReviewHarnessOfferTest extends RepositoryReviewWithFixture {

    private static final String CLAUDE = "claude_code";

    /** Claude Code configured, as SPIRE_FACTORY_AGENT_IMAGE_CLAUDE_CODE would; a profile is per class. */
    public static final class ClaudeCodeImage implements QuarkusTestProfile {
        @Override
        public Map<String, String> getConfigOverrides() {
            return Map.of("spire.factory.agent-image." + CLAUDE, "TEST-claude-code-image");
        }
    }

    @Test
    void aNewHarnessChoiceOfAModelItDoesNotRunIs400() {
        RepositoryInput openaiOnClaude = repositoryReviewingOn(new ReviewHarness(CLAUDE, "TEST-cat-o", null, "API_KEY"));

        given().contentType("application/json").body(openaiOnClaude).post("/api/repositories")
                .then().statusCode(400).body(containsString("model_not_run_by_harness"));
    }

    @Test
    void aNewHarnessChoiceOfAModelItRunsSaves() {
        RepositoryInput anthropicOnClaude = repositoryReviewingOn(new ReviewHarness(CLAUDE, "TEST-cat-a", null, "API_KEY"));

        given().contentType("application/json").body(anthropicOnClaude).post("/api/repositories")
                .then().statusCode(201).body("reviewHarness.model", equalTo("TEST-cat-a"));
    }

    @Test
    void aNewHarnessChoiceOfALevelTheModelDoesNotOfferIs400() {
        RepositoryInput levelNotOffered = repositoryReviewingOn(new ReviewHarness(CODEX, "TEST-other-model", "high", "API_KEY"));

        given().contentType("application/json").body(levelNotOffered).post("/api/repositories")
                .then().statusCode(400).body(containsString("effort_not_offered"));
    }

    /** A row saved before the check (or by SQL) is not a new choice: saving another setting keeps it. */
    @Test
    void anUnchangedStoredHarnessChoiceSavesWhenAnotherSettingChanges() throws Exception {
        RepositoryView repo = repositories.create(repository(null, null));
        execute("UPDATE repository SET review_harness=?, review_harness_model=?, review_harness_pay_with='API_KEY'"
                + " WHERE id=?", CLAUDE, "TEST-cat-o", repo.id());
        RepositoryInput onlyEnabledChanged = new RepositoryInput("gitlab", origin, workspace, "TEST-repo", false, null,
                null, null, null, new ReviewHarness(CLAUDE, "TEST-cat-o", null, "API_KEY"));

        given().contentType("application/json").body(onlyEnabledChanged)
                .put("/api/repositories/" + repo.id() + "?revision=1").then().statusCode(200)
                .body("enabled", equalTo(false)).body("reviewHarness.model", equalTo("TEST-cat-o"));
    }
}
