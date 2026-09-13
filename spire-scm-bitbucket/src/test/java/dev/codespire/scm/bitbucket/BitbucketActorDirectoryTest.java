package dev.codespire.scm.bitbucket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.port.ActorDirectory;
import org.junit.jupiter.api.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class BitbucketActorDirectoryTest {
    static WireMockServer server;
    BitbucketActorDirectory directory;
    static final String PERSON="{\"account_id\":\"TEST-stable\",\"nickname\":\"TEST-person\",\"display_name\":\"TEST Person\"}";
    @BeforeAll static void start() { server=new WireMockServer(WireMockConfiguration.options().dynamicPort());server.start(); }
    @AfterAll static void stop() { server.stop(); }
    @BeforeEach void reset() {
        server.resetAll();directory=new BitbucketActorDirectory(new BitbucketCloudClient(
                new BitbucketCloudConfig(server.baseUrl(),null,null,"TEST-token","TEST-hook"),new ObjectMapper()));
    }
    void stub(String path,String json) { server.stubFor(get(urlEqualTo(path)).willReturn(okJson(json))); }
    @Test void duplicateNicknamesRequireSelectionAcrossPages() {
        stub("/workspaces/TEST-workspace/members?pagelen=100&page=1","{\"values\":[{\"user\":"+PERSON+"}],\"next\":\""+server.baseUrl()+"/workspaces/TEST-workspace/members?page=2\"}");
        stub("/workspaces/TEST-workspace/members?pagelen=100&page=2","{\"values\":[{\"user\":"+PERSON.replace("TEST-stable","TEST-other")+"}]}");
        var result=directory.lookup("@TEST-person","TEST-workspace");
        assertEquals(ActorDirectory.Status.SELECTION_REQUIRED,result.status());assertEquals(2,result.actors().size());
        assertEquals("TEST-stable",result.actors().getFirst().providerUserId());
        server.verify(getRequestedFor(urlEqualTo("/workspaces/TEST-workspace/members?pagelen=100&page=2"))
                .withHeader("Authorization",equalTo("Bearer TEST-token")));
    }
    @Test void missingWorkspaceHasAnExplicitCapabilityError() {
        var result=directory.lookup("@TEST-person",null);
        assertEquals(ActorDirectory.Status.UNSUPPORTED,result.status());assertTrue(result.detail().contains("credential"));
        assertEquals(ActorDirectory.Status.UNSUPPORTED,directory.lookup("TEST-person"," ").status());
        server.verify(0,getRequestedFor(urlMatching(".*")));
    }
    @Test void refusesIncompleteOrUnboundedMemberLists() {
        stub("/workspaces/TEST-workspace/members?pagelen=100&page=1","{}");
        assertEquals(ActorDirectory.Status.UNAVAILABLE,directory.lookup("TEST-person","TEST-workspace").status());
        for(int page=1;page<=10;page++)stub("/workspaces/TEST-workspace/members?pagelen=100&page="+page,"{\"values\":[],\"next\":\"more\"}");
        stub("/workspaces/TEST-workspace/members?pagelen=100&page=11","{\"values\":[{\"user\":"+PERSON+"}]}");
        assertEquals(ActorDirectory.Status.UNAVAILABLE,directory.lookup("TEST-person","TEST-workspace").status());
        server.verify(0,getRequestedFor(urlEqualTo("/workspaces/TEST-workspace/members?pagelen=100&page=11")));
    }
    @Test void byIdMustReturnTheRequestedAccount() {
        stub("/users/TEST-stable",PERSON.replace("TEST-stable","TEST-other"));
        assertEquals(ActorDirectory.Status.UNAVAILABLE,directory.byId("TEST-stable").status());
        stub("/users/TEST-stable",PERSON);
        assertEquals(ActorDirectory.Status.FOUND,directory.byId("TEST-stable").status());
    }
    @Test void identityRedirectCannotCrossTheOrigin() {
        WireMockServer foreign=new WireMockServer(WireMockConfiguration.options().dynamicPort());foreign.start();
        try {
            foreign.stubFor(get(urlEqualTo("/TEST-person")).willReturn(okJson(PERSON)));
            server.stubFor(get(urlEqualTo("/users/TEST-stable")).willReturn(aResponse().withStatus(302).withHeader("Location",foreign.baseUrl()+"/TEST-person")));
            assertEquals(ActorDirectory.Status.UNAVAILABLE,directory.byId("TEST-stable").status());
            foreign.verify(0,getRequestedFor(urlMatching(".*")));
        } finally {foreign.stop();}
    }
    @Test void memberIdentityNeedsAnIdAndReadableLabel() {
        stub("/workspaces/TEST-workspace/members?pagelen=100&page=1","{\"values\":[{\"user\":{\"nickname\":\"TEST-person\"}}]}");
        assertEquals(ActorDirectory.Status.UNAVAILABLE,directory.lookup("TEST-person","TEST-workspace").status());
        stub("/workspaces/TEST-workspace/members?pagelen=100&page=1","{\"values\":[{\"user\":{\"account_id\":\"TEST-stable\"}}]}");
        assertEquals(ActorDirectory.Status.UNAVAILABLE,directory.lookup("TEST-person","TEST-workspace").status());
    }
    @Test void unrelatedMembersCannotBecomeCandidates() {
        stub("/workspaces/TEST-workspace/members?pagelen=100&page=1","{\"values\":[{\"user\":"+PERSON+"}]}");
        assertEquals(ActorDirectory.Status.NOT_FOUND,directory.lookup("TEST-other","TEST-workspace").status());
        assertEquals(ActorDirectory.Status.SELECTION_REQUIRED,directory.lookup("TEST Person","TEST-workspace").status());
        assertEquals(ActorDirectory.Status.SELECTION_REQUIRED,directory.lookup("TEST-person","TEST-workspace").status());
    }
    @Test void blankInputDoesNotQueryTheDirectory() {
        assertEquals(ActorDirectory.Status.NOT_FOUND,directory.lookup("@ ","TEST-workspace").status());
        server.verify(0,getRequestedFor(urlMatching(".*")));
    }
}
