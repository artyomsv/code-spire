package dev.codespire.scm.bitbucket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.RepositoryPermission;
import org.junit.jupiter.api.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.codespire.contract.scm.RepositoryPermission.State.*;

class BitbucketRepositoryPermissionSourceTest {
    static WireMockServer server;
    BitbucketRepositoryPermissionSource source;
    final RepoRef repo = new RepoRef("TEST-owner", "TEST-repo");
    @BeforeAll static void start() { server = new WireMockServer(WireMockConfiguration.options().dynamicPort()); server.start(); }
    @AfterAll static void stop() { server.stop(); }
    @BeforeEach void reset() { server.resetAll(); source = new BitbucketRepositoryPermissionSource(new BitbucketCloudClient(new BitbucketCloudConfig(server.baseUrl(), null, null, "TEST-token", "unused"), new ObjectMapper())); stub("/users/900123", "{\"account_id\":\"900123\",\"uuid\":\"{TEST-uuid}\"}"); }
    void stub(String path, String json) { server.stubFor(get(urlEqualTo(path)).willReturn(okJson(json))); }
    RepositoryPermission result() { return source.permission(repo, "900123"); }
    static final String PATH = "/workspaces/TEST-owner/permissions/repositories/TEST-repo?pagelen=100";
    String entry(String permission) { return "{\"user\":{\"uuid\":\"{TEST-uuid}\"},\"repository\":{\"full_name\":\"TEST-owner/TEST-repo\"},\"permission\":\""+permission+"\"}"; }
    void response(String json) { stub(PATH,json); }
    String page(String entry) { return "{\"values\":["+entry+"]}"; }
    @Test void inheritedWriteAccessIsRecognized() {
        response("{\"values\":[],\"next\":\""+server.baseUrl()+"/TEST-next?cursor=TEST-cursor\"}");
        stub("/TEST-next?cursor=TEST-cursor",page(entry("write"))); assertEquals(CAN_PUSH,result().state());
        server.verify(getRequestedFor(urlEqualTo(PATH)).withHeader("Authorization",equalTo("Bearer TEST-token")));
        server.verify(getRequestedFor(urlEqualTo("/TEST-next?cursor=TEST-cursor")).withHeader("Authorization",equalTo("Bearer TEST-token")));
    }
    @Test void unknownResponseCannotGrant() { response(page(entry("TEST-custom"))); assertEquals(UNKNOWN,result().state()); }
    @Test void readerCannotPush() { response(page(entry("read"))); assertEquals(CANNOT_PUSH,result().state()); }
    @Test void missingMemberHasNoWriteAccess() { response(page(entry("write").replace("{TEST-uuid}","{TEST-other}"))); assertEquals(CANNOT_PUSH,result().state()); }
    @Test void differentReturnedIdentityCannotGrant() { stub("/users/900123","{\"account_id\":\"900999\",\"uuid\":\"{TEST-uuid}\"}"); response(page(entry("write"))); assertEquals(UNKNOWN,result().state()); }
    @Test void missingUuidCannotGrant() { stub("/users/900123","{\"account_id\":\"900123\"}"); response(page(entry("write"))); assertEquals(UNKNOWN,result().state()); }
    @Test void missingValuesCannotGrant() { response("{}"); assertEquals(UNKNOWN,result().state()); }
    @Test void malformedMemberCannotBeSkipped() { response(page(entry("read").replace("\"uuid\":\"{TEST-uuid}\"", "")+","+entry("write"))); assertEquals(UNKNOWN,result().state()); }
    @Test void differentRepositoryCannotGrant() { response(page(entry("write").replace("TEST-repo","TEST-other"))); assertEquals(UNKNOWN,result().state()); }
    @Test void conflictingAccountIdCannotGrant() { response(page(entry("write").replace("{\"uuid\":", "{\"account_id\":\"900999\",\"uuid\":"))); assertEquals(UNKNOWN,result().state()); }
    @Test void contradictoryPagesCannotGrant() {
        response("{\"values\":["+entry("write")+"],\"next\":\""+server.baseUrl()+"/TEST-next\"}"); stub("/TEST-next",page(entry("read"))); assertEquals(UNKNOWN,result().state());
    }
    @Test void incompletePaginationCannotGrant() {
        response("{\"values\":["+entry("write")+"],\"next\":\""+server.baseUrl()+"/TEST-next\"}"); server.stubFor(get(urlEqualTo("/TEST-next")).willReturn(aResponse().withStatus(403))); assertEquals(UNKNOWN,result().state());
    }
    @Test void malformedNextCannotGrant() { response("{\"values\":["+entry("write")+"],\"next\":false}"); assertEquals(UNKNOWN,result().state()); }
    @Test void paginationCycleCannotGrant() {
        String page="{\"values\":["+entry("write")+"],\"next\":\""+server.baseUrl()+"/TEST-next\"}";
        response(page);stub("/TEST-next",page); assertEquals(UNKNOWN,result().state()); server.verify(1,getRequestedFor(urlEqualTo("/TEST-next")));
    }
    @Test void foreignPaginationCannotGrantOrReceiveToken() {
        WireMockServer foreign=new WireMockServer(WireMockConfiguration.options().dynamicPort());foreign.start();
        try {
            response("{\"values\":[],\"next\":\""+foreign.baseUrl()+"/TEST-page\"}");
            foreign.stubFor(get(urlEqualTo("/TEST-page")).willReturn(okJson(page(entry("write")))));
            assertEquals(UNKNOWN,result().state());foreign.verify(0,getRequestedFor(urlMatching(".*")));
        } finally { foreign.stop(); }
    }
    @Test void pageLimitCannotGrant() {
        response("{\"values\":["+entry("write")+"],\"next\":\""+server.baseUrl()+"/TEST-page-1\"}");
        for(int i=1;i<=10;i++)stub("/TEST-page-"+i,"{\"values\":[],\"next\":\""+server.baseUrl()+"/TEST-page-"+(i+1)+"\"}");
        assertEquals(UNKNOWN,result().state());server.verify(0,getRequestedFor(urlEqualTo("/TEST-page-10")));
    }
    @Test void completeTenthPageCanGrant() {
        response("{\"values\":["+entry("write")+"],\"next\":\""+server.baseUrl()+"/TEST-page-1\"}");
        for(int i=1;i<9;i++)stub("/TEST-page-"+i,"{\"values\":[],\"next\":\""+server.baseUrl()+"/TEST-page-"+(i+1)+"\"}");
        stub("/TEST-page-9","{\"values\":[]}");
        assertEquals(CAN_PUSH,result().state());server.verify(0,getRequestedFor(urlEqualTo("/TEST-page-10")));
    }
    @Test void failedReadCannotReuseEarlierPermission() {
        response(page(entry("write")));assertEquals(CAN_PUSH,result().state());
        for (int status : new int[]{403,404,429,503}) { server.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status))); RepositoryPermission measured=result();assertEquals(UNKNOWN,measured.state());assertTrue(measured.detail().contains("repository-admin")); }
    }
    @Test void malformedResponseCannotGrant() { response("{"); assertEquals(UNKNOWN,result().state()); }
}
