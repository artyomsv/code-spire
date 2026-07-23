package dev.codespire.orchestrator.prompt;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;

@QuarkusTest
class PromptResourceTest {

    @Test
    void listReturnsThreeKinds() {
        given().when().get("/api/prompts")
                .then().statusCode(200).body("size()", is(3));
    }

    @Test
    void putRejectsMissingRequiredVariable() {
        given().contentType("application/json")
                .body("{\"system\":\"s\",\"body\":\"no diff here\"}")
                .when().put("/api/prompts/review")
                .then().statusCode(400).body(containsString("diff"));
    }

    @Test
    void putThenResetRoundTrips() {
        given().contentType("application/json")
                .body("{\"system\":\"custom\",\"body\":\"review {{diff}}\"}")
                .when().put("/api/prompts/review")
                .then().statusCode(200).body("customized", is(true));

        given().when().delete("/api/prompts/review").then().statusCode(204);
        given().when().get("/api/prompts/review").then().statusCode(200).body("customized", is(false));
    }

    @Test
    void previewAnnotatesSlots() {
        given().contentType("application/json")
                .body("{\"system\":\"s\",\"body\":\"review {{diff}}\"}")
                .when().post("/api/prompts/review/preview")
                .then().statusCode(200).body("user", containsString("«diff"));
    }

    @Test
    void unknownKindIs404() {
        given().when().get("/api/prompts/bogus").then().statusCode(404);
    }
}
