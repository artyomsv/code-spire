package dev.codespire.orchestrator.repository;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** V120 "Verdict labels": off by default, stored through create and update, read back by the saga. */
@QuarkusTest
@TestSecurity(user = "TEST-admin", roles = {"spire-viewer", "spire-admin"})
class RepositoryVerdictLabelsTest extends RepositoryFixture {

    @Test
    void verdictLabelsDefaultOffAndRoundTripThroughUpdate() {
        String id = given().contentType("application/json").body(repository(null, null)).post("/api/repositories")
                .then().statusCode(201).body("verdictLabels", equalTo(false)).extract().path("id");
        given().contentType("application/json").body(labelled(true))
                .put("/api/repositories/" + id + "?revision=1").then().statusCode(200).body("verdictLabels", equalTo(true));
        given().get("/api/repositories/" + id).then().statusCode(200).body("verdictLabels", equalTo(true));
        assertEquals(Optional.of(true), repositories.verdictLabels(UUID.fromString(id)));
        given().contentType("application/json").body(labelled(false))
                .put("/api/repositories/" + id + "?revision=2").then().statusCode(200).body("verdictLabels", equalTo(false));
        assertEquals(Optional.of(false), repositories.verdictLabels(UUID.fromString(id)));
    }

    @Test
    void aCreateMayTurnThemOn() {
        given().contentType("application/json").body(labelled(true)).post("/api/repositories")
                .then().statusCode(201).body("verdictLabels", equalTo(true));
    }

    /** An older client sends no field: the update keeps what is stored, as "Block approval at" does. */
    @Test
    void anUpdateThatOmitsTheSettingKeepsIt() {
        String id = given().contentType("application/json").body(labelled(true)).post("/api/repositories")
                .then().statusCode(201).extract().path("id");
        given().contentType("application/json").body(repository(null, null))
                .put("/api/repositories/" + id + "?revision=1").then().statusCode(200).body("verdictLabels", equalTo(true));
    }

    @Test
    void aMissingRowIsEmptyNeverADefault() {
        assertTrue(repositories.verdictLabels(UUID.randomUUID()).isEmpty());
    }

    private RepositoryInput labelled(boolean on) {
        return new RepositoryInput("gitlab", origin, workspace, "TEST-repo", true, null, null, null, null, null, null,
                null, on);
    }
}
