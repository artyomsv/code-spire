package dev.codespire.scm.github;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.port.ActorDirectory;
import org.junit.jupiter.api.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class GitHubActorDirectoryTest {
    static WireMockServer server;
    GitHubActorDirectory directory;
    @BeforeAll static void start() { server=new WireMockServer(WireMockConfiguration.options().dynamicPort());server.start(); }
    @AfterAll static void stop() { server.stop(); }
    @BeforeEach void reset() { server.resetAll();directory=new GitHubActorDirectory(new GitHubClient(new GitHubConfig(server.baseUrl(), "TEST-token", null),new ObjectMapper())); }
    void stub(String path,String json) { server.stubFor(get(urlEqualTo(path)).willReturn(okJson(json))); }
    @Test void exactHandleUsesTheConfiguredCredential() {
        stub("/users/TEST-person","{\"id\":900123,\"login\":\"TEST-person\",\"name\":\"TEST Person\"}");
        var result=directory.lookup("@TEST-person",null);
        assertEquals(ActorDirectory.Status.FOUND,result.status());
        assertEquals("900123",result.actors().getFirst().providerUserId());
        assertEquals("TEST-person",result.actors().getFirst().handle());
        server.verify(getRequestedFor(urlEqualTo("/users/TEST-person")).withHeader("Authorization",equalTo("Bearer TEST-token")));
    }
    @Test void refusesANonexactHandle() {
        stub("/users/TEST-person","{\"id\":900123,\"login\":\"TEST-other\"}");
        assertEquals(ActorDirectory.Status.NOT_FOUND,directory.lookup("@TEST-person",null).status());
    }
    @Test void rejectsIncompleteIdentity() {
        stub("/users/TEST-person","{}");
        assertEquals(ActorDirectory.Status.UNAVAILABLE,directory.lookup("@TEST-person",null).status());
    }
    @Test void refreshCannotReplaceTheStoredId() {
        stub("/user/900123","{\"id\":900456,\"login\":\"TEST-person\"}");
        assertEquals(ActorDirectory.Status.UNAVAILABLE,directory.byId("900123").status());
    }
    @Test void refreshFollowsTheStableIdThroughARename() {
        stub("/user/900123","{\"id\":900123,\"login\":\"TEST-renamed\",\"name\":\"TEST Renamed\"}");
        var result=directory.byId("900123");
        assertEquals(ActorDirectory.Status.FOUND,result.status());
        assertEquals("900123",result.actors().getFirst().providerUserId());
        assertEquals("TEST-renamed",result.actors().getFirst().handle());
        server.verify(0,getRequestedFor(urlEqualTo("/users/TEST-person")));
    }
    @Test void directoryFailureNeverBecomesAnIdentity() {
        server.stubFor(get(urlEqualTo("/users/TEST-person")).willReturn(aResponse().withStatus(403).withBody("TEST-secret-in-upstream-body")));
        var result=directory.lookup("@TEST-person",null);
        assertEquals(ActorDirectory.Status.UNAVAILABLE,result.status());
        assertTrue(result.actors().isEmpty());assertFalse(result.detail().contains("TEST-secret"));
    }
    @Test void identityRedirectCannotCrossTheOrigin() {
        WireMockServer foreign=new WireMockServer(WireMockConfiguration.options().dynamicPort());foreign.start();
        try {
            foreign.stubFor(get(urlEqualTo("/TEST-person")).willReturn(okJson("{\"id\":900123,\"login\":\"TEST-person\",\"name\":\"TEST Person\"}")));
            server.stubFor(get(urlEqualTo("/users/TEST-person")).willReturn(aResponse().withStatus(302).withHeader("Location",foreign.baseUrl()+"/TEST-person")));
            assertEquals(ActorDirectory.Status.UNAVAILABLE,directory.lookup("@TEST-person",null).status());
            foreign.verify(0,getRequestedFor(urlMatching(".*")));
        } finally { foreign.stop(); }
    }

    @Test void missingStableIdCannotBeAResolvedPerson() {
        stub("/users/TEST-person","{\"login\":\"TEST-person\"}");
        assertEquals(ActorDirectory.Status.UNAVAILABLE,directory.lookup("TEST-person",null).status());
    }
    @Test void missingHandleCannotBeAResolvedPerson() {
        stub("/users/TEST-person","{\"id\":900123,\"name\":\"TEST Person\"}");
        assertEquals(ActorDirectory.Status.UNAVAILABLE,directory.lookup("TEST-person",null).status());
    }
    @Test void blankInputDoesNotQueryTheDirectory() {
        assertEquals(ActorDirectory.Status.NOT_FOUND,directory.lookup("@ ",null).status());
        server.verify(0,getRequestedFor(urlMatching(".*")));
    }
}
