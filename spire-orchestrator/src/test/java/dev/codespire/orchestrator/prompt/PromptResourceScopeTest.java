package dev.codespire.orchestrator.prompt;

import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import io.restassured.response.ValidatableResponse;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;

/**
 * The REST surface over the (scope, kind) prompt registry (Task 20/21): every endpoint now accepts
 * {@code ?scope=}, resolving repository -> global -> built-in default the way {@link PromptRegistry}
 * already does, and {@code /api/prompts/scopes} lists the repositories an override could be written
 * for. {@code inheritedFrom} is the field that makes a repo-scoped view honest -- a repo scope with
 * no override of its own renders identically to global, and this is the only way the operator can
 * tell which row actually supplied the text.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class PromptResourceScopeTest {

    private static final String REPO_SCOPE = "acme/widgets";

    @Inject
    PromptRegistry registry;

    @Inject
    ReviewProjection projection;

    @Inject
    DataSource dataSource;

    @AfterEach
    void resetScopes() {
        registry.reset(PromptKind.REVIEW);
        registry.reset(PromptKind.REVIEW, REPO_SCOPE);
    }

    @Test
    void readingAtARepoScopeSaysWhereTheTextCameFrom() {
        saveGlobal("Global persona", "review {{diff}}");

        getReview(REPO_SCOPE)
                .statusCode(200)
                .body("scope", is(REPO_SCOPE))
                .body("inheritedFrom", is("global"))
                .body("system", is("Global persona"));
    }

    @Test
    void savingAtARepoScopeDoesNotTouchGlobal() {
        saveGlobal("Global persona", "review {{diff}}");

        putReview(REPO_SCOPE, "Repo persona", "review {{diff}}").statusCode(200);

        getReview(PromptScope.GLOBAL).statusCode(200).body("system", is("Global persona"));
        getReview(REPO_SCOPE).statusCode(200).body("inheritedFrom", is("repo"));
    }

    @Test
    void aMalformedScopeIs400NotAStoredKeyNobodyCanAddress() {
        putReview("../../etc", "x", "review {{diff}}").statusCode(400);
    }

    /**
     * Ruling: {@code accept-default} must also be scoped -- it wasn't in the brief's five, but it
     * re-stamps a customization's recorded ancestor, and after the (scope, kind) re-key a
     * customization is per scope. An unscoped accept would clear the drift flag on whichever row
     * Postgres happened to return, not the one the operator was looking at.
     */
    @Test
    void acceptDefaultAtARepoScopeLeavesGlobalDriftAlone() {
        insertRowWithBase(PromptScope.GLOBAL, "Global persona", "review {{diff}}",
                "AN OLDER GLOBAL PERSONA", "review {{diff}}");
        insertRowWithBase(REPO_SCOPE, "Repo persona", "review {{diff}}",
                "AN OLDER REPO PERSONA", "review {{diff}}");

        given().queryParam("scope", REPO_SCOPE)
                .when().post("/api/prompts/review/accept-default")
                .then().statusCode(204);

        getReview(REPO_SCOPE).statusCode(200).body("defaultDrifted", is(false));
        getReview(PromptScope.GLOBAL).statusCode(200).body("defaultDrifted", is(true));
    }

    @Test
    void scopesListsRepositoriesTheOrchestratorHasSeen() {
        registerReview("acme", "widgets", 7);

        given().when().get("/api/prompts/scopes")
                .then().statusCode(200).body("$", hasItem(REPO_SCOPE));
    }

    @Test
    @TestSecurity(user = "test-viewer", roles = {"spire-viewer"})
    void aViewerCannotListScopes() {
        // Every registry read is admin-only (ADR-022): a listing is an inventory of every repository
        // the deployment reaches.
        given().when().get("/api/prompts/scopes").then().statusCode(403);
    }

    private void saveGlobal(String system, String body) {
        putReview(PromptScope.GLOBAL, system, body).statusCode(200);
    }

    private ValidatableResponse getReview(String scope) {
        return given().queryParam("scope", scope)
                .when().get("/api/prompts/review")
                .then();
    }

    private ValidatableResponse putReview(String scope, String system, String body) {
        return given().contentType(ContentType.JSON).queryParam("scope", scope)
                .body("{\"system\":\"" + system + "\",\"body\":\"" + body + "\"}")
                .when().put("/api/prompts/review")
                .then();
    }

    private void registerReview(String workspace, String slug, long pr) {
        RepoRef repo = new RepoRef(workspace, slug);
        String reviewId = ReviewIds.reviewId(repo, pr);
        projection.registerHeader(reviewId, repo, pr, "Sample PR " + pr, "alice", "a1",
                "feature", "main", "commit" + pr, "https://scm.example/" + repo.full() + "/pull/" + pr,
                "bitbucket-cloud", "reviewing", ReviewProjection.STAGE_DIFF);
    }

    private void insertRowWithBase(String scope, String system, String body, String baseSystem, String baseBody) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO prompt_template
                         (scope, kind, system_text, body_text, base_system_text, base_body_text, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, now())
                     ON CONFLICT (scope, kind) DO UPDATE
                         SET system_text      = EXCLUDED.system_text,
                             body_text        = EXCLUDED.body_text,
                             base_system_text = EXCLUDED.base_system_text,
                             base_body_text   = EXCLUDED.base_body_text,
                             updated_at       = now()
                     """)) {
            ps.setString(1, scope);
            ps.setString(2, PromptKind.REVIEW.slug());
            ps.setString(3, system);
            ps.setString(4, body);
            ps.setString(5, baseSystem);
            ps.setString(6, baseBody);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert row with base for scope " + scope, e);
        }
    }
}
