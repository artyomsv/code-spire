package dev.codespire.orchestrator.operator;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.restassured.RestAssured;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The self-service half of the operator mapping (P4 / FR-11).
 *
 * <p>Who may do what is the point. Starting a sign-in is viewer-work — everything it can produce is
 * a link from the caller's OWN subject to an account the platform just confirmed they control — while
 * setting up the application is configuration, and configuration is admin-only including its reads
 * (ADR-022's third rule).
 *
 * <p>Redirects are not followed. Each assertion here is about the answer this service gives, and
 * following a 303 to a real SCM would make these tests depend on the internet.
 */
@QuarkusTest
class OperatorConnectResourceTest {

    private static final String ALICE = "TEST-SUBJECT-ALICE";

    @Inject
    ScmOAuthApps apps;

    @Inject
    ConnectStates states;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void clean() {
        exec("DELETE FROM scm_oauth_app WHERE provider_type = 'github'");
        exec("DELETE FROM oauth_connect_state WHERE oidc_subject LIKE 'TEST-SUBJECT-%'");
        exec("DELETE FROM operator_identity WHERE oidc_subject LIKE 'TEST-SUBJECT-%'");
    }

    @Test
    @TestSecurity(user = ALICE, roles = "spire-viewer")
    void aViewerCanSeeWhichPlatformsTheyMayConnect() {
        given().when().get("/api/operator-connect")
                .then().statusCode(200)
                .body("providerType", hasItem("github"));
    }

    /**
     * A platform with no application set up reports itself unconfigured rather than absent, so the
     * interface can say an admin has a step to take instead of silently offering nothing.
     */
    @Test
    @TestSecurity(user = ALICE, roles = "spire-viewer")
    void reportsAPlatformWithNoApplicationAsUnconfigured() {
        given().when().get("/api/operator-connect")
                .then().statusCode(200)
                .body("find { it.providerType == 'github' }.configured", is(false));
    }

    @Test
    @TestSecurity(user = ALICE, roles = "spire-viewer")
    void refusesToStartASignInForAPlatformThatIsNotSetUp() {
        given().redirects().follow(false)
                .when().get("/api/operator-connect/github/start")
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = ALICE, roles = "spire-viewer")
    void refusesToStartASignInForAPlatformThatDoesNotExist() {
        given().redirects().follow(false)
                .when().get("/api/operator-connect/not-a-platform/start")
                .then().statusCode(404);
    }

    @Test
    @TestSecurity(user = ALICE, roles = "spire-viewer")
    void sendsTheOperatorToThePlatformOnceAnApplicationIsSetUp() {
        apps.save(new ScmOAuthApps.Input("github", null, null, "TEST-CLIENT", "TEST-SECRET"));

        String location = given().redirects().follow(false)
                .when().get("/api/operator-connect/github/start")
                .then().statusCode(303).extract().header("Location");

        org.junit.jupiter.api.Assertions.assertTrue(location.contains("/login/oauth/authorize"));
        org.junit.jupiter.api.Assertions.assertTrue(location.contains("client_id=TEST-CLIENT"));
        // The redirect address is derived from the request, so it names this deployment rather than
        // a configured value that could fall out of step with how the browser actually reached it.
        org.junit.jupiter.api.Assertions.assertTrue(
                location.contains("operator-connect%2Fgithub%2Fcallback"), location);
    }

    /**
     * The attack this whole mechanism exists to stop: a callback URL from somebody else's attempt.
     * Redeeming it would link THEIR account to the operator who clicked, leaving that operator
     * measured as a different person with nothing on screen looking wrong.
     */
    @Test
    @TestSecurity(user = ALICE, roles = "spire-viewer")
    void refusesACallbackWhoseStateBelongsToAnotherOperator() {
        apps.save(new ScmOAuthApps.Input("github", null, null, "TEST-CLIENT", "TEST-SECRET"));
        String theirState = states.start("TEST-SUBJECT-SOMEBODY-ELSE", "github",
                "https://spire.example.invalid/cb");

        given().redirects().follow(false)
                .when().get("/api/operator-connect/github/callback?code=TEST-CODE&state=" + theirState)
                .then().statusCode(303).header("Location", containsString("connect=mismatch"));

        assertEquals(0, linkCount(), "a refused callback must write no link at all");
    }

    @Test
    @TestSecurity(user = ALICE, roles = "spire-viewer")
    void refusesACallbackWithAStateThatWasNeverIssued() {
        given().redirects().follow(false)
                .when().get("/api/operator-connect/github/callback?code=TEST-CODE&state=TEST-NOBODY-ISSUED")
                .then().statusCode(303).header("Location", containsString("connect=expired"));

        assertEquals(0, linkCount());
    }

    /** The operator said no at the consent screen. Not an error, and it must not read as one. */
    @Test
    @TestSecurity(user = ALICE, roles = "spire-viewer")
    void reportsADeclinedSignInAsItsOwnOutcome() {
        given().redirects().follow(false)
                .when().get("/api/operator-connect/github/callback?error=access_denied&state=anything")
                .then().statusCode(303).header("Location", containsString("connect=declined"));
    }

    /**
     * A state that was issued to this operator but arrives with no code. The state is still spent,
     * because a redeemable state left behind is the replay this binding exists to prevent.
     */
    @Test
    @TestSecurity(user = ALICE, roles = "spire-viewer")
    void refusesACallbackCarryingNoCode() {
        String mine = states.start(ALICE, "github", "https://spire.example.invalid/cb");

        given().redirects().follow(false)
                .when().get("/api/operator-connect/github/callback?state=" + mine)
                .then().statusCode(303).header("Location", containsString("connect=nocode"));

        given().redirects().follow(false)
                .when().get("/api/operator-connect/github/callback?code=TEST-CODE&state=" + mine)
                .then().statusCode(303).header("Location", containsString("connect=expired"));
    }

    /** Setting up an application is configuration, so its reads are admin-only like every registry. */
    @Test
    @TestSecurity(user = ALICE, roles = "spire-viewer")
    void aViewerCannotSeeOrChangeTheApplications() {
        given().when().get("/api/scm-oauth-apps").then().statusCode(403);
        given().contentType("application/json")
                .body("{\"providerType\":\"github\",\"clientId\":\"x\",\"clientSecret\":\"y\"}")
                .when().post("/api/scm-oauth-apps").then().statusCode(403);
    }

    @Test
    @TestSecurity(user = "TEST-SUBJECT-ADMIN", roles = {"spire-viewer", "spire-admin"})
    void anAdminSeesEveryPlatformWithTheAddressToRegister() {
        given().when().get("/api/scm-oauth-apps")
                .then().statusCode(200)
                .body("find { it.providerType == 'github' }.redirectUri",
                        containsString("/api/operator-connect/github/callback"));
    }

    @Test
    @TestSecurity(user = "TEST-SUBJECT-ADMIN", roles = {"spire-viewer", "spire-admin"})
    void refusesAnApplicationForAPlatformThisBuildCannotSignInTo() {
        given().contentType("application/json")
                .body("{\"providerType\":\"bitbucket-dc\",\"clientId\":\"x\",\"clientSecret\":\"y\"}")
                .when().post("/api/scm-oauth-apps")
                .then().statusCode(400);
    }

    /** A first save has nothing to keep, so a missing secret is a rejection rather than a blank one. */
    @Test
    @TestSecurity(user = "TEST-SUBJECT-ADMIN", roles = {"spire-viewer", "spire-admin"})
    void refusesAFirstApplicationWithNoSecret() {
        given().contentType("application/json")
                .body("{\"providerType\":\"github\",\"clientId\":\"x\",\"clientSecret\":\"\"}")
                .when().post("/api/scm-oauth-apps")
                .then().statusCode(400);
    }

    private int linkCount() {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT count(*) FROM operator_identity WHERE oidc_subject = ?")) {
            ps.setString(1, ALICE);
            try (var rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not count links", e);
        }
    }

    private void exec(String sql) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + sql, e);
        }
    }

    static {
        RestAssured.enableLoggingOfRequestAndResponseIfValidationFails();
    }
}
