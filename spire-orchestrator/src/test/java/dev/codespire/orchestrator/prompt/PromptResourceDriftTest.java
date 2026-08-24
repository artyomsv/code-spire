package dev.codespire.orchestrator.prompt;

import dev.codespire.contract.llm.PromptCatalog;
import dev.codespire.contract.llm.PromptKind;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * The drift Task 17 computed on {@link PromptRegistry} now has to reach the API: a UI cannot show
 * "the shipped default moved since you customized this" from a boolean that never leaves the
 * server. {@code baseKnown} and {@code defaultDrifted} are asserted separately from each other
 * (and from the plain {@code customized}/{@code system} fields {@link PromptResourceTest} already
 * covers) so a wiring mistake that swaps one for the other, or that only advances one of the two,
 * cannot pass silently.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class PromptResourceDriftTest {

    @Inject
    DataSource dataSource;

    @AfterEach
    void resetReview() {
        given().when().delete("/api/prompts/review");
    }

    @Test
    void aDriftedKindSaysSoAndCarriesBothSidesOfTheDiff() {
        insertRowWithBase(PromptKind.REVIEW, "My persona", "Diff:\n{{diff}}",
                "AN OLDER SHIPPED PERSONA", "Diff:\n{{diff}}");

        given().when().get("/api/prompts/review")
                .then().statusCode(200)
                .body("defaultDrifted", is(true))
                .body("baseKnown", is(true))
                .body("baseSystem", is("AN OLDER SHIPPED PERSONA"))
                .body("currentDefaultSystem",
                        is(PromptCatalog.defaultTemplate(PromptKind.REVIEW).system()));
    }

    @Test
    void acceptDefaultClearsTheFlagWithoutChangingTheEffectiveTemplate() {
        insertRowWithBase(PromptKind.REVIEW, "My persona", "Diff:\n{{diff}}",
                "AN OLDER SHIPPED PERSONA", "Diff:\n{{diff}}");

        given().when().post("/api/prompts/review/accept-default")
                .then().statusCode(204);

        given().when().get("/api/prompts/review")
                .then().statusCode(200)
                .body("defaultDrifted", is(false))
                .body("system", is("My persona"));
    }

    @Test
    @TestSecurity(user = "test-viewer", roles = {"spire-viewer"})
    void aViewerCannotAcceptTheDefault() {
        given().when().post("/api/prompts/review/accept-default")
                .then().statusCode(403);
    }

    @Test
    void aLegacyRowWithNoRecordedAncestorReportsUnknownThroughTheApi() {
        // A row as V23 alone would have written it -- no ancestor column populated. Unlike
        // PromptRegistryDriftTest's equivalent, this goes through the real HTTP GET so a wiring
        // mistake in effective() (e.g. a hardcoded baseKnown=true) cannot pass silently: nothing
        // else in this class ever asserts baseKnown=false through the API.
        insertLegacyRowWithoutBase(PromptKind.REVIEW, "Old persona", "Diff:\n{{diff}}");

        given().when().get("/api/prompts/review")
                .then().statusCode(200)
                .body("baseKnown", is(false))
                .body("defaultDrifted", is(false)); // unknowable, so not asserted either way
    }

    private void insertLegacyRowWithoutBase(PromptKind kind, String system, String body) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO prompt_template (kind, system_text, body_text, updated_at)
                     VALUES (?, ?, ?, now())
                     ON CONFLICT (scope, kind) DO UPDATE
                         SET system_text       = EXCLUDED.system_text,
                             body_text         = EXCLUDED.body_text,
                             base_system_text  = NULL,
                             base_body_text    = NULL,
                             updated_at        = now()
                     """)) {
            ps.setString(1, kind.slug());
            ps.setString(2, system);
            ps.setString(3, body);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert legacy row for " + kind.slug(), e);
        }
    }

    private void insertRowWithBase(PromptKind kind, String system, String body,
            String baseSystem, String baseBody) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO prompt_template
                         (kind, system_text, body_text, base_system_text, base_body_text, updated_at)
                     VALUES (?, ?, ?, ?, ?, now())
                     ON CONFLICT (scope, kind) DO UPDATE
                         SET system_text       = EXCLUDED.system_text,
                             body_text         = EXCLUDED.body_text,
                             base_system_text  = EXCLUDED.base_system_text,
                             base_body_text    = EXCLUDED.base_body_text,
                             updated_at        = now()
                     """)) {
            ps.setString(1, kind.slug());
            ps.setString(2, system);
            ps.setString(3, body);
            ps.setString(4, baseSystem);
            ps.setString(5, baseBody);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to insert row with base for " + kind.slug(), e);
        }
    }
}
