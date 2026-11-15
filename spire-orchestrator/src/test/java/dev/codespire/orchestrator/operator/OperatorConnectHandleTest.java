package dev.codespire.orchestrator.operator;

import dev.codespire.contract.port.OperatorOAuth;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.OAuthApp;
import dev.codespire.orchestrator.analytics.OperatorIdentities;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The SCM login a finished sign-in keeps beside the proved id (V116).
 *
 * <p>Found on the first production install: a linked account read {@code 3218389} over {@code 3218389},
 * because the only handle came from authors observed in reviews. The id stays the key; the login is
 * display data. The forge is a fake {@link OperatorOAuth}, so no test reaches the internet. Same fixture
 * as {@link OperatorConnectResourceTest}.
 */
@QuarkusTest
class OperatorConnectHandleTest {

    private static final String ALICE = "TEST-SUBJECT-ALICE";

    @Inject
    ScmOAuthApps apps;

    @Inject
    ConnectStates states;

    @Inject
    OperatorIdentities identities;

    @Inject
    DataSource dataSource;

    @BeforeEach
    void clean() {
        exec("DELETE FROM scm_oauth_app WHERE provider_type = 'github'");
        exec("DELETE FROM oauth_connect_state WHERE oidc_subject LIKE 'TEST-SUBJECT-%'");
        exec("DELETE FROM operator_identity WHERE oidc_subject LIKE 'TEST-SUBJECT-%' OR oidc_subject = ''");
    }

    @Test
    @TestSecurity(user = ALICE, roles = "spire-viewer")
    void aFinishedSignInStoresTheObservedLoginBesideTheId() {
        connectAs(Author.of("TEST-900001", "TEST-alice-login", "TEST Alice"));

        assertEquals(List.of(new OperatorIdentities.Link(ALICE, "github", "TEST-900001", "TEST-alice-login")),
                identities.forSubject(ALICE));
    }

    /** A login that is an email address is not stored, nor is the author's email: the handle stays empty. */
    @Test
    @TestSecurity(user = ALICE, roles = "spire-viewer")
    void aLoginThatIsAnEmailIsNotStored() {
        connectAs(new Author("TEST-900002", "test-alice@example.invalid", "TEST Alice", "test-alice@example.invalid"));

        assertEquals(List.of(new OperatorIdentities.Link(ALICE, "github", "TEST-900002", null)),
                identities.forSubject(ALICE));
    }

    /** An admin's repair of the same account by hand keeps the observed login; a different account drops it. */
    @Test
    void aManualRelinkKeepsTheLoginOnlyForTheSameAccount() {
        identities.link(new OperatorIdentities.Link(ALICE, "github", "TEST-900001", "TEST-alice-login"));

        identities.link(new OperatorIdentities.Link(ALICE, "github", "TEST-900001", null));
        assertEquals("TEST-alice-login", identities.forSubject(ALICE).getFirst().authorHandle());

        identities.link(new OperatorIdentities.Link(ALICE, "github", "TEST-900003", null));
        assertNull(identities.forSubject(ALICE).getFirst().authorHandle());
    }

    @Test
    @TestSecurity(user = "TEST-SUBJECT-ADMIN", roles = {"spire-viewer", "spire-admin"})
    void theAdminListReturnsTheObservedLogin() {
        identities.link(new OperatorIdentities.Link(ALICE, "github", "TEST-900001", "TEST-alice-login"));

        given().when().get("/api/operator-identities")
                .then().statusCode(200)
                .body("find { it.oidcSubject == '" + ALICE + "' }.authorHandle", is("TEST-alice-login"));
    }

    /** Runs a whole connect for ALICE against a forge that answers with {@code author}. */
    private void connectAs(Author author) {
        apps.save(new ScmOAuthApps.Input("github", null, null, "TEST-CLIENT", "TEST-SECRET"));
        QuarkusMock.installMockForType(new OperatorConnects() {
            @Override
            public Optional<OperatorOAuth> forType(ScmType type) {
                return Optional.of(forgeAnswering(author));
            }
        }, OperatorConnects.class);
        String mine = states.start(ALICE, "github", "https://spire.example.invalid/cb");

        given().redirects().follow(false)
                .when().get("/api/operator-connect/github/callback?code=TEST-CODE&state=" + mine)
                .then().statusCode(303).header("Location", containsString("connect=connected"));
    }

    private static OperatorOAuth forgeAnswering(Author author) {
        return new OperatorOAuth() {
            @Override
            public ScmType type() {
                return ScmType.GITHUB;
            }

            @Override
            public String authorizeUrl(OAuthApp app, String state, String redirectUri) {
                return "https://forge.example.invalid/authorize";
            }

            @Override
            public Author identify(OAuthApp app, String code, String redirectUri) {
                return author;
            }
        };
    }

    private void exec(String sql) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("setup failed: " + sql, e);
        }
    }
}
