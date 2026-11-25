package dev.codespire.orchestrator.repository;

import dev.codespire.orchestrator.review.checks.StartReviewSetting;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** V121 "Start review": IMMEDIATELY by default, stored through create and update, read back by the start gate. */
@QuarkusTest
@TestSecurity(user = "TEST-admin", roles = {"spire-viewer", "spire-admin"})
class RepositoryStartReviewTest extends RepositoryFixture {

    @Inject StartReviewSetting setting;

    @Test
    void startReviewDefaultsToImmediatelyAndRoundTripsThroughUpdate() {
        String id = given().contentType("application/json").body(repository(null, null)).post("/api/repositories")
                .then().statusCode(201).body("startReview", equalTo("IMMEDIATELY")).extract().path("id");
        given().contentType("application/json").body(starting("after_checks"))
                .put("/api/repositories/" + id + "?revision=1").then().statusCode(200).body("startReview", equalTo("AFTER_CHECKS"));
        assertEquals(StartReview.AFTER_CHECKS, setting.of(UUID.fromString(id)));
        given().contentType("application/json").body(starting("IMMEDIATELY"))
                .put("/api/repositories/" + id + "?revision=2").then().statusCode(200).body("startReview", equalTo("IMMEDIATELY"));
        assertEquals(StartReview.IMMEDIATELY, setting.of(UUID.fromString(id)));
    }

    /** An older client sends no field: the update keeps what is stored, as "Verdict labels" does. */
    @Test
    void anUpdateThatOmitsTheSettingKeepsIt() {
        String id = given().contentType("application/json").body(starting("AFTER_CHECKS")).post("/api/repositories")
                .then().statusCode(201).extract().path("id");
        given().contentType("application/json").body(repository(null, null))
                .put("/api/repositories/" + id + "?revision=1").then().statusCode(200).body("startReview", equalTo("AFTER_CHECKS"));
    }

    @Test
    void anUnknownValueIsRefusedWithItsReason() {
        given().contentType("application/json").body(starting("TEST-sometimes")).post("/api/repositories")
                .then().statusCode(400).body(containsString("Start review"));
    }

    @Test
    void aMissingRowStartsAtOnce() {
        assertEquals(StartReview.IMMEDIATELY, setting.of(UUID.randomUUID()));
    }

    private RepositoryInput starting(String value) {
        return new RepositoryInput("gitlab", origin, workspace, "TEST-repo", true, null, null, null, null, null, null,
                null, null, value);
    }
}
