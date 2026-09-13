package dev.codespire.orchestrator.provider;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.nullValue;

/**
 * {@code GET /api/providers/serving}: which registrations serve a (forge type, workspace), per role,
 * in one of five states — the answer the Repositories screen shows beside each row.
 *
 * <p>Rows are written through the registry rather than the REST path, because the REST path
 * resolves a login from the token owner and this test needs rows WITHOUT one: the two amber states
 * exist to name exactly that condition. Every workspace is unique per test so the shared Dev
 * Services database never makes two tests see each other's rows.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class ProviderServingResourceTest {

    @Inject
    ProviderRegistry registry;

    private static String workspace(String label) {
        return "TEST-serving-" + label + "-" + UUID.randomUUID();
    }

    private static ProviderInput row(String workspace, String name, String role, String botAccountId,
                                     String botUsername, boolean enabled) {
        return new ProviderInput(name, "github", "https://api.github.com", "bearer", null,
                "TEST-token-" + name, botAccountId, enabled, List.of(), botUsername, null, role);
    }

    @Inject dev.codespire.orchestrator.repository.RepositoryRegistry repositories;
    private final java.util.Map<String, java.util.UUID> repositoryIds = new java.util.HashMap<>();

    private java.util.UUID repository(String workspace) {
        return repositoryIds.computeIfAbsent(workspace, ws -> repositories.create(
                new dev.codespire.orchestrator.repository.RepositoryInput("github", "https://api.github.com",
                        ws, "TEST-repo", true, null, null)).id());
    }

    private void register(String workspace, ProviderInput input) {
        var account = registry.create(input);
        var repo = repositories.get(repository(workspace)).orElseThrow();
        boolean factory = "FACTORY".equals(input.role());
        repositories.update(repo.id(), repo.revision(), new dev.codespire.orchestrator.repository.RepositoryInput(
                repo.scmType(), repo.forgeOrigin(), workspace, repo.slug(), true,
                factory ? (repo.reviewer() == null ? null : repo.reviewer().id()) : UUID.fromString(account.id()),
                factory ? UUID.fromString(account.id()) : (repo.factory() == null ? null : repo.factory().id())));
    }

    private io.restassured.response.ValidatableResponse serving(String workspace) {
        return given().queryParam("repositoryId", repository(workspace))
                .when().get("/api/providers/serving")
                .then().statusCode(200)
                .body("type", equalTo("github"))
                .body("workspace", equalTo(workspace));
    }

    @Test
    void nothingRegisteredIsMissingOnBothRoles() {
        String ws = workspace("none");
        serving(ws)
                .body("reviewer.state", equalTo("missing"))
                .body("reviewer.id", nullValue())
                .body("factory.state", equalTo("missing"))
                .body("factory.name", nullValue());
    }

    @Test
    void anEnabledReviewerWithAnIdentityIsOk() {
        String ws = workspace("rev-ok");
        register(ws, row(ws, "TEST-reviewer", null, "TEST-acct-1", "test-bot", true));
        serving(ws)
                .body("reviewer.state", equalTo("ok"))
                .body("reviewer.name", equalTo("TEST-reviewer"))
                .body("reviewer.botUsername", equalTo("test-bot"))
                .body("reviewer.botAccountId", equalTo("TEST-acct-1"))
                .body("factory.state", equalTo("missing"));
    }

    /** The condition under which ConversationSaga skips every follow-up: the bot cannot recognise itself. */
    @Test
    void aReviewerWithNoResolvedIdentityIsFlaggedNotGreen() {
        String ws = workspace("rev-noid");
        register(ws, row(ws, "TEST-reviewer", null, "", null, true));
        serving(ws).body("reviewer.state", equalTo("no-identity"));
    }

    /**
     * The identity the self-loop guard compares against is the account id, not the login. A row
     * whose token could not name a login is still a recognisable bot, and must not be flagged.
     */
    @Test
    void aReviewerIdentifiedByAccountIdAloneIsOk() {
        String ws = workspace("rev-id-only");
        register(ws, row(ws, "TEST-reviewer", null, "TEST-acct-1", null, true));
        serving(ws).body("reviewer.state", equalTo("ok"));
    }

    /** Registered-but-disabled has a different cure from missing, so it is a different word. */
    @Test
    void aDisabledRowIsDisabledNotMissing() {
        String ws = workspace("disabled");
        register(ws, row(ws, "TEST-reviewer", null, "TEST-acct-1", "test-bot", false));
        register(ws, row(ws, "TEST-factory", "FACTORY", "TEST-acct-2", "test-factory", false));
        serving(ws)
                .body("reviewer.state", equalTo("disabled"))
                .body("reviewer.name", equalTo("TEST-reviewer"))
                .body("factory.state", equalTo("disabled"))
                .body("factory.name", equalTo("TEST-factory"));
    }

    /** The same filter MachineAccounts.resolve applies before a dispatch: no login, no push. */
    @Test
    void aFactoryWithNoLoginCannotPush() {
        String ws = workspace("fac-nologin");
        register(ws, row(ws, "TEST-factory", "FACTORY", "TEST-acct-2", null, true));
        serving(ws)
                .body("factory.state", equalTo("no-login"))
                .body("factory.name", equalTo("TEST-factory"))
                .body("reviewer.state", equalTo("missing"));
    }

    @Test
    void aFactoryWithALoginIsOk() {
        String ws = workspace("fac-ok");
        register(ws, row(ws, "TEST-factory", "FACTORY", "TEST-acct-2", "test-factory", true));
        serving(ws)
                .body("factory.state", equalTo("ok"))
                .body("factory.botUsername", equalTo("test-factory"));
    }

    @Test
    void theResponseCarriesNoSecretField() {
        String ws = workspace("nosecret");
        register(ws, row(ws, "TEST-reviewer", null, "TEST-acct-1", "test-bot", true));
        serving(ws)
                .body("reviewer.secret", nullValue())
                .body("reviewer.authSecret", nullValue())
                .body("reviewer.hasSecret", nullValue());
    }

    @Test
    void blankParametersAreA400() {
        given().queryParam("type", "").queryParam("workspace", "x")
                .when().get("/api/providers/serving").then().statusCode(400);
        given().queryParam("type", "github")
                .when().get("/api/providers/serving").then().statusCode(400);
    }

    /**
     * A type no adapter serves would otherwise be answered {@code missing} on both roles, which
     * names a cure the operator cannot carry out. The wrong case is the realistic form of it: the
     * registry stores the lowercase type, so "Github" matches no row and reads as an empty
     * workspace rather than as a typo.
     */
    @Test
    void anUnknownForgeTypeIsA400() {
        given().queryParam("type", "Github").queryParam("workspace", "TEST-wrong-case")
                .when().get("/api/providers/serving").then().statusCode(400);
    }

    /** A registry read is an inventory of the deployment's reach; viewers are refused, like every other. */
    @Test
    @TestSecurity(user = "test-viewer", roles = {"spire-viewer"})
    void aViewerIsRefused() {
        given().queryParam("type", "github").queryParam("workspace", "TEST-viewer")
                .when().get("/api/providers/serving").then().statusCode(403);
    }
}
