package dev.codespire.orchestrator.analytics;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.http.ContentType;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.is;

/**
 * Who may read whose numbers (P4 / FR-11).
 *
 * <p>Per-author analytics is performance data about a named person, so the authorization rule is the
 * feature's sharpest edge. It is row-level — "a viewer may read their own row" — which
 * {@code @RolesAllowed} cannot express, so the rule lives in code and therefore needs tests that
 * exercise it rather than an annotation anyone can read.
 */
@QuarkusTest
class AnalyticsAuthorizationTest {

    private static final String ALICE = "TEST-SUBJECT-ALICE";
    private static final String BOB = "TEST-SUBJECT-BOB";

    @Inject
    DataSource dataSource;

    @BeforeEach
    void clean() {
        exec("DELETE FROM operator_identity WHERE oidc_subject LIKE 'TEST-SUBJECT-%'");
    }

    /**
     * The failure this rule exists to prevent, stated as a test: one operator reading another's
     * performance data. Nothing in the UI would look wrong if it were allowed.
     */
    @Test
    @TestSecurity(user = ALICE, roles = "spire-viewer")
    void aViewerCannotReadAnotherOperatorsActivity() {
        link(BOB, "github", "bob-scm-id");

        given().when().get("/api/analytics/authors/github/bob-scm-id")
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = ALICE, roles = "spire-viewer")
    void aViewerCanReadTheIdentityTheyAreMappedTo() {
        link(ALICE, "github", "alice-scm-id");

        given().when().get("/api/analytics/authors/github/alice-scm-id")
                .then().statusCode(200);
    }

    /**
     * An unmapped operator matches nothing and is refused — never defaulted into somebody else's
     * numbers, which is what "fail open" would mean for a privacy control.
     */
    @Test
    @TestSecurity(user = ALICE, roles = "spire-viewer")
    void anUnmappedViewerIsRefusedRatherThanDefaulted() {
        given().when().get("/api/analytics/authors/github/anyone-at-all")
                .then().statusCode(403);
    }

    /**
     * The mapping is per platform. The same author id on two SCMs is two unrelated people — the
     * collision this project has already been bitten by twice.
     */
    @Test
    @TestSecurity(user = ALICE, roles = "spire-viewer")
    void aMappingOnOnePlatformDoesNotGrantTheSameIdOnAnother() {
        link(ALICE, "github", "shared-id");

        given().when().get("/api/analytics/authors/gitlab/shared-id")
                .then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "TEST-SUBJECT-ADMIN", roles = {"spire-viewer", "spire-admin"})
    void anAdminCanReadAnyAuthor() {
        given().when().get("/api/analytics/authors/github/somebody-else")
                .then().statusCode(200);
    }

    /**
     * Unlinked is its own answer, not an empty chart. An empty chart reads as "you have done
     * nothing"; this reads as "we do not know who you are", and they send an operator to different
     * places — the distinction the ADR-025 {@code refused} incident charged for.
     */
    @Test
    @TestSecurity(user = ALICE, roles = "spire-viewer")
    void anUnmappedOperatorGetsTheUnlinkedStateRatherThanZeroes() {
        given().when().get("/api/analytics/me")
                .then().statusCode(200).body("linked", is(false));
    }

    @Test
    @TestSecurity(user = ALICE, roles = "spire-viewer")
    void aMappedOperatorSeesTheirOwnIdentityOnTheirActivityView() {
        link(ALICE, "github", "alice-scm-id");

        given().when().get("/api/analytics/me")
                .then().statusCode(200)
                .body("linked", is(true))
                .body("providerType", is("github"))
                .body("authorId", is("alice-scm-id"));
    }

    /** The registry is admin-only including its reads: it maps real people to measured activity. */
    @Test
    @TestSecurity(user = ALICE, roles = "spire-viewer")
    void aViewerCannotEvenListTheIdentityMappings() {
        given().when().get("/api/operator-identities").then().statusCode(403);
    }

    /**
     * A blank provider type would map to no review at all, leaving the operator permanently unlinked
     * with nothing explaining why — so it is refused at the boundary rather than stored.
     */
    @Test
    @TestSecurity(user = "TEST-SUBJECT-ADMIN", roles = {"spire-viewer", "spire-admin"})
    void aMappingWithoutAPlatformIsRefused() {
        given().contentType(ContentType.JSON)
                .body("{\"oidcSubject\":\"" + ALICE + "\",\"providerType\":\"\",\"authorId\":\"x\"}")
                .when().post("/api/operator-identities")
                .then().statusCode(400);
    }

    private void link(String subject, String providerType, String authorId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO operator_identity (oidc_subject, provider_type, author_id)"
                             + " VALUES (?, ?, ?) ON CONFLICT DO NOTHING")) {
            ps.setString(1, subject);
            ps.setString(2, providerType);
            ps.setString(3, authorId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("could not seed the mapping", e);
        }
    }

    private void exec(String sql) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + sql, e);
        }
    }
}
