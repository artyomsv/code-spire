package dev.codespire.context.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.port.ActorDirectory;
import org.junit.jupiter.api.*;
import java.util.Set;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class JiraActorDirectoryTest {
    static WireMockServer server;
    JiraActorDirectory directory;
    static final String PERSON="{\"accountId\":\"TEST-stable\",\"displayName\":\"TEST Person\",\"active\":true}";
    @BeforeAll static void start(){server=new WireMockServer(WireMockConfiguration.options().dynamicPort());server.start();}
    @AfterAll static void stop(){server.stop();}
    @BeforeEach void reset(){server.resetAll();directory=new JiraActorDirectory(new JiraClient(new JiraConfig(server.baseUrl(),"bearer",null,"TEST-token",Set.of()),new ObjectMapper()));}
    void stub(String path,String json){server.stubFor(get(urlEqualTo(path)).willReturn(okJson(json)));}
    @Test void displayNameIsAlwaysAnExplicitSelection() {
        stub("/rest/api/3/user/search?query=TEST%20Person&maxResults=50","["+PERSON+","+PERSON.replace("TEST-stable","TEST-other")+"]");
        var result=directory.lookup("TEST Person",null);
        assertEquals(ActorDirectory.Status.SELECTION_REQUIRED,result.status());assertEquals(2,result.actors().size());
        assertEquals("TEST-stable",result.actors().getFirst().providerUserId());assertEquals("",result.actors().getFirst().handle());
        server.verify(getRequestedFor(urlEqualTo("/rest/api/3/user/search?query=TEST%20Person&maxResults=50")).withHeader("Authorization",equalTo("Bearer TEST-token")));
    }
    @Test void noCloudAccountIdIsAnExplicitCapabilityError() {
        stub("/rest/api/3/user/search?query=TEST%20Person&maxResults=50","[{\"name\":\"TEST-legacy\",\"displayName\":\"TEST Person\",\"active\":true}]");
        var result=directory.lookup("TEST Person",null);
        assertEquals(ActorDirectory.Status.UNSUPPORTED,result.status());assertTrue(result.detail().contains("Cloud account IDs"));
    }
    @Test void inactivePersonCannotBeSelected() {
        stub("/rest/api/3/user/search?query=TEST%20Person&maxResults=50","["+PERSON.replace("true","false")+"]");
        assertEquals(ActorDirectory.Status.NOT_FOUND,directory.lookup("TEST Person",null).status());
    }
    @Test void refreshRequiresTheSameActiveAccount() {
        stub("/rest/api/3/user?accountId=TEST-stable",PERSON.replace("TEST-stable","TEST-other"));
        assertEquals(ActorDirectory.Status.UNAVAILABLE,directory.byId("TEST-stable").status());
        stub("/rest/api/3/user?accountId=TEST-stable",PERSON.replace("true","false"));
        assertEquals(ActorDirectory.Status.UNAVAILABLE,directory.byId("TEST-stable").status());
        stub("/rest/api/3/user?accountId=TEST-stable",PERSON);
        assertEquals(ActorDirectory.Status.FOUND,directory.byId("TEST-stable").status());
    }
    @Test void identityRedirectCannotCrossTheOrigin() {
        WireMockServer foreign=new WireMockServer(WireMockConfiguration.options().dynamicPort());foreign.start();
        try {
            foreign.stubFor(get(urlEqualTo("/TEST-person")).willReturn(okJson(PERSON)));
            server.stubFor(get(urlEqualTo("/rest/api/3/user?accountId=TEST-stable")).willReturn(aResponse().withStatus(302).withHeader("Location",foreign.baseUrl()+"/TEST-person")));
            assertEquals(ActorDirectory.Status.UNAVAILABLE,directory.byId("TEST-stable").status());
            foreign.verify(0,getRequestedFor(urlMatching(".*")));
        } finally {foreign.stop();}
    }
    @Test void missingDisplayNameIsNotASelectablePerson() {
        stub("/rest/api/3/user/search?query=TEST%20Person&maxResults=50","[{\"accountId\":\"TEST-stable\",\"active\":true}]");
        assertEquals(ActorDirectory.Status.UNSUPPORTED,directory.lookup("TEST Person",null).status());
    }
    @Test void nonArrayResponseIsACapabilityError() {
        stub("/rest/api/3/user/search?query=TEST%20Person&maxResults=50","{}");
        assertEquals(ActorDirectory.Status.UNSUPPORTED,directory.lookup("TEST Person",null).status());
    }
    @Test void blankInputDoesNotQueryTheDirectory() {
        assertEquals(ActorDirectory.Status.NOT_FOUND,directory.lookup("@ ",null).status());
        server.verify(0,getRequestedFor(urlMatching(".*")));
    }
}
