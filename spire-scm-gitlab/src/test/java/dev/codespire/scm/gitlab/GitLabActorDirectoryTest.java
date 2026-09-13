package dev.codespire.scm.gitlab;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.port.ActorDirectory;
import org.junit.jupiter.api.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class GitLabActorDirectoryTest {
    static WireMockServer server;
    GitLabActorDirectory directory;
    @BeforeAll static void start() { server=new WireMockServer(WireMockConfiguration.options().dynamicPort());server.start(); }
    @AfterAll static void stop() { server.stop(); }
    @BeforeEach void reset() { server.resetAll();directory=new GitLabActorDirectory(new GitLabClient(new GitLabConfig(server.baseUrl(), "TEST-token"),new ObjectMapper())); }
    void stub(String path,String json) { server.stubFor(get(urlEqualTo(path)).willReturn(okJson(json))); }
    @Test void exactHandleUsesTheConfiguredCredential() {
        stub("/users?username=TEST-person&per_page=100","[{\"id\":900123,\"username\":\"TEST-person\",\"name\":\"TEST Person\"}]");
        var result=directory.lookup("@TEST-person",null);
        assertEquals(ActorDirectory.Status.FOUND,result.status());
        assertEquals("900123",result.actors().getFirst().providerUserId());
        assertEquals("TEST-person",result.actors().getFirst().handle());
        server.verify(getRequestedFor(urlEqualTo("/users?username=TEST-person&per_page=100")).withHeader("Authorization",equalTo("Bearer TEST-token")));
    }
    @Test void refusesANonexactHandle() {
        stub("/users?username=TEST-person&per_page=100","[{\"id\":900123,\"username\":\"TEST-other\"}]");
        assertEquals(ActorDirectory.Status.NOT_FOUND,directory.lookup("@TEST-person",null).status());
    }
    @Test void rejectsIncompleteIdentity() {
        stub("/users?username=TEST-person&per_page=100","[{}]");
        assertEquals(ActorDirectory.Status.UNAVAILABLE,directory.lookup("@TEST-person",null).status());
    }
    @Test void refreshCannotReplaceTheStoredId() {
        stub("/users/900123","{\"id\":900456,\"username\":\"TEST-person\"}");
        assertEquals(ActorDirectory.Status.UNAVAILABLE,directory.byId("900123").status());
    }
    @Test void refreshFollowsTheStableIdThroughARename() {
        stub("/users/900123","{\"id\":900123,\"username\":\"TEST-renamed\",\"name\":\"TEST Renamed\"}");
        var result=directory.byId("900123");
        assertEquals(ActorDirectory.Status.FOUND,result.status());
        assertEquals("900123",result.actors().getFirst().providerUserId());
        assertEquals("TEST-renamed",result.actors().getFirst().handle());
        server.verify(0,getRequestedFor(urlEqualTo("/users?username=TEST-person&per_page=100")));
    }
    @Test void directoryFailureNeverBecomesAnIdentity() {
        server.stubFor(get(urlEqualTo("/users?username=TEST-person&per_page=100")).willReturn(aResponse().withStatus(403).withBody("TEST-secret-in-upstream-body")));
        var result=directory.lookup("@TEST-person",null);
        assertEquals(ActorDirectory.Status.UNAVAILABLE,result.status());
        assertTrue(result.actors().isEmpty());assertFalse(result.detail().contains("TEST-secret"));
    }
    @Test void identityRedirectCannotCrossTheOrigin() {
        WireMockServer foreign=new WireMockServer(WireMockConfiguration.options().dynamicPort());foreign.start();
        try {
            foreign.stubFor(get(urlEqualTo("/TEST-person")).willReturn(okJson("[{\"id\":900123,\"username\":\"TEST-person\",\"name\":\"TEST Person\"}]")));
            server.stubFor(get(urlEqualTo("/users?username=TEST-person&per_page=100")).willReturn(aResponse().withStatus(302).withHeader("Location",foreign.baseUrl()+"/TEST-person")));
            assertEquals(ActorDirectory.Status.UNAVAILABLE,directory.lookup("@TEST-person",null).status());
            foreign.verify(0,getRequestedFor(urlMatching(".*")));
        } finally { foreign.stop(); }
    }

    @Test void missingStableIdCannotBeAResolvedPerson() {
        stub("/users?username=TEST-person&per_page=100","[{\"username\":\"TEST-person\"}]");
        assertEquals(ActorDirectory.Status.UNAVAILABLE,directory.lookup("TEST-person",null).status());
    }
    @Test void missingHandleCannotBeAResolvedPerson() {
        stub("/users?username=TEST-person&per_page=100","[{\"id\":900123,\"name\":\"TEST Person\"}]");
        assertEquals(ActorDirectory.Status.UNAVAILABLE,directory.lookup("TEST-person",null).status());
    }
    @Test void blankInputDoesNotQueryTheDirectory() {
        assertEquals(ActorDirectory.Status.NOT_FOUND,directory.lookup("@ ",null).status());
        server.verify(0,getRequestedFor(urlMatching(".*")));
    }

    @Test void multipleExactMatchesAreAmbiguous() {
        stub("/users?username=TEST-person&per_page=100","[{\"id\":900123,\"username\":\"TEST-person\"},{\"id\":900456,\"username\":\"TEST-person\"}]");
        assertEquals(ActorDirectory.Status.AMBIGUOUS,directory.lookup("TEST-person",null).status());
    }
    @Test void nonArrayOrFullPageIsIncompleteEvidence() {
        stub("/users?username=TEST-person&per_page=100","{}");
        assertEquals(ActorDirectory.Status.UNAVAILABLE,directory.lookup("TEST-person",null).status());
        String user="{\"id\":900123,\"username\":\"TEST-other\"}";
        stub("/users?username=TEST-person&per_page=100","["+String.join(",",java.util.Collections.nCopies(100,user))+"]");
        assertEquals(ActorDirectory.Status.UNAVAILABLE,directory.lookup("TEST-person",null).status());
    }
}
