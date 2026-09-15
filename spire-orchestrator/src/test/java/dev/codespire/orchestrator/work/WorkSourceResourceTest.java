package dev.codespire.orchestrator.work;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.Test;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;

@QuarkusTest
class WorkSourceResourceTest extends WorkFixture {
    String endpoint(){return "/api/work-sources/"+source;}
    @Test @TestSecurity(user="TEST-admin",roles="spire-admin")
    void actorLookupUsesTheSelectedTrackerAndDoesNotSaveItsResult() {
        given().contentType("application/json").body("{\"handle\":\"TEST-person\"}").post(endpoint()+"/actors/resolve")
                .then().statusCode(200).body("status",is("FOUND")).body("actors[0].providerUserId",is("900123"));
        assertEquals(2,sources.get(source).orElseThrow().version().source());
    }
    @Test @TestSecurity(user="TEST-admin",roles="spire-admin")
    void listedSourceNamesItsAllowedPeopleByHandleAsWellAsId() {
        // An id alone is unrecognisable in the allowlist panel; the handle confirmed at save time is what an operator reads.
        given().get("/api/work-sources").then().statusCode(200)
                .body("find { it.id == '"+source+"' }.allowedPeople.providerUserId",contains("900123"))
                .body("find { it.id == '"+source+"' }.allowedPeople.handle",contains("TEST-person"));
    }
    @Test @TestSecurity(user="TEST-admin",roles="spire-admin")
    void blankPersonQueryIsRejectedBeforeTheTracker() {
        forge.resetRequests();given().contentType("application/json").body("{\"handle\":\"  \"}")
                .post(endpoint()+"/actors/resolve").then().statusCode(400);
        forge.verify(0,getRequestedFor(urlMatching("/users/.*")));
    }
    @Test @TestSecurity(user="TEST-admin",roles="spire-admin")
    void capabilitiesDescribeOperationsWithoutGrantingPermissions() {
        given().get(endpoint()+"/capabilities").then().statusCode(200)
                .body("operations",hasItems("CANDIDATES","LABEL_AUDIT","COMMENT","TRANSITION"))
                .body("detail",containsString("does not grant access"))
                .body("detail",containsString("authenticated issue webhooks"));
    }
    @Test @TestSecurity(user="TEST-admin",roles="spire-admin")
    void disabledSourceCannotResolvePeople() throws Exception {
        execute("UPDATE work_source SET enabled=false WHERE id=?",source);forge.resetRequests();
        given().contentType("application/json").body("{\"handle\":\"TEST-person\"}").post(endpoint()+"/actors/resolve").then().statusCode(400);
        forge.verify(0,getRequestedFor(urlMatching("/users/.*")));
    }
    @Test @TestSecurity(user="TEST-admin",roles="spire-admin")
    void disabledSourceReportsUnavailableCapabilities() throws Exception {
        execute("UPDATE work_source SET enabled=false WHERE id=?",source);
        given().get(endpoint()+"/capabilities").then().statusCode(503);
    }
    @Test @TestSecurity(user="TEST-viewer",roles="spire-viewer")
    void viewerCannotInspectCapabilitiesOrResolvePeople() {
        given().get(endpoint()+"/capabilities").then().statusCode(403);
        given().contentType("application/json").body("{\"handle\":\"TEST-person\"}").post(endpoint()+"/actors/resolve").then().statusCode(403);
    }
    @Test void unauthenticatedCallerCannotUseEitherEndpoint() {
        given().get(endpoint()+"/capabilities").then().statusCode(401);
        given().contentType("application/json").body("{\"handle\":\"TEST-person\"}").post(endpoint()+"/actors/resolve").then().statusCode(401);
    }
    @Test void configuredEnablementSurvivesAnUnavailableAccount() throws Exception {
        execute("UPDATE scm_provider SET enabled=false WHERE id=?",account);
        var unavailable=sources.get(source).orElseThrow();assertFalse(unavailable.enabled());assertTrue(unavailable.configuredEnabled());
    }
    @Test void anUnavailableSourceCanStillBeDisabled() throws Exception {
        execute("UPDATE scm_provider SET enabled=false WHERE id=?",account);
        var saved=assertDoesNotThrow(()->administration.edit(source,new WorkSourceAdministration.Edit("TEST-disabled-source",account,false,2)));
        assertFalse(saved.configuredEnabled());assertFalse(saved.enabled());assertEquals(3,saved.version().source());
    }
}
