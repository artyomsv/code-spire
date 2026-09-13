package dev.codespire.orchestrator.repository;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@TestSecurity(user = "TEST-admin", roles = {"spire-viewer", "spire-admin"})
class RepositoryResourceTest extends RepositoryFixture {
    @Test void registersARepositoryWithExplicitRoleBindings() {
        UUID reviewer = account("REVIEWER"), factory = account("FACTORY");
        String id = given().contentType("application/json").body(repository(reviewer, factory)).post("/api/repositories")
                .then().statusCode(201).body("workspace", equalTo(workspace))
                .body("reviewer.id", equalTo(reviewer.toString())).body("factory.id", equalTo(factory.toString()))
                .body(not(containsString("TEST-secret"))).extract().path("id");
        given().get("/api/repositories/" + id).then().statusCode(200)
                .body("forgeOrigin", equalTo(origin)).body("factory.handle", equalTo("TEST-login-FACTORY"));
    }

    @Test void staleUpdateCannotOverwriteBindings() {
        UUID reviewer = account("REVIEWER");
        var repo = repositories.create(repository(reviewer, null));
        given().contentType("application/json").body(repository(null, null)).put("/api/repositories/" + repo.id() + "?revision=1").then().statusCode(200);
        given().contentType("application/json").body(repository(reviewer, null)).put("/api/repositories/" + repo.id() + "?revision=1")
                .then().statusCode(409).body(containsString("reload"));
        assertNull(repositories.get(repo.id()).orElseThrow().reviewer());
    }

    @Test void duplicateCoordinatesCannotCreateSecondRepository() {
        repositories.create(repository(null, null));
        given().contentType("application/json").body(repository(null, null)).post("/api/repositories").then().statusCode(409);
    }

    @Test void missingKindIsBadRequest() {
        given().contentType("application/json").body("{}").post("/api/repositories").then().statusCode(400);
    }

    @Test @TestSecurity(user = "TEST-viewer", roles = "spire-viewer")
    void viewerCannotRegisterRepository() {
        given().contentType("application/json").body(repository(null, null)).post("/api/repositories").then().statusCode(403);
    }
}
