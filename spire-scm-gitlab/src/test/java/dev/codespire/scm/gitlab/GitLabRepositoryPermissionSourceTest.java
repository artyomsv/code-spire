package dev.codespire.scm.gitlab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.RepositoryPermission;
import org.junit.jupiter.api.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.codespire.contract.scm.RepositoryPermission.State.*;

class GitLabRepositoryPermissionSourceTest {
    static WireMockServer server;
    GitLabRepositoryPermissionSource source;
    final RepoRef repo = new RepoRef("TEST-owner", "TEST-repo");
    @BeforeAll static void start() { server = new WireMockServer(WireMockConfiguration.options().dynamicPort()); server.start(); }
    @AfterAll static void stop() { server.stop(); }
    @BeforeEach void reset() { server.resetAll(); source = new GitLabRepositoryPermissionSource(new GitLabClient(new GitLabConfig(server.baseUrl(), "TEST-token"), new ObjectMapper()));  }
    void stub(String path, String json) { server.stubFor(get(urlEqualTo(path)).willReturn(okJson(json))); }
    RepositoryPermission result() { return source.permission(repo, "900123"); }
    static final String PATH = "/projects/TEST-owner%2FTEST-repo/members/all/900123";
    void response(String json) { stub(PATH,json); }
    String member(String access, String state, String expiry) { return "{\"id\":900123,\"state\":\""+state+"\",\"access_level\":"+access+",\"expires_at\":"+expiry+"}"; }
    @Test void inheritedWriteAccessIsRecognized() {
        response(member("30","active","null")); assertEquals(CAN_PUSH,result().state());
        server.verify(getRequestedFor(urlEqualTo(PATH)).withHeader("Authorization",equalTo("Bearer TEST-token")));
        server.verify(0,getRequestedFor(urlEqualTo("/projects/TEST-owner%2FTEST-repo/members/900123")));
    }
    @Test void unknownResponseCannotGrant() { response(member("99","active","null")); assertEquals(UNKNOWN,result().state()); }
    @Test void readerCannotPush() { response(member("20","active","null")); assertEquals(CANNOT_PUSH,result().state()); }
    @Test void differentReturnedIdentityCannotGrant() { response(member("30","active","null").replace("900123","900999")); assertEquals(UNKNOWN,result().state()); }
    @Test void inactiveMemberCannotGrant() { response(member("30","blocked","null")); assertEquals(UNKNOWN,result().state()); }
    @Test void expiredMemberCannotPush() { response(member("30","active","\"2000-01-01\"")); assertEquals(CANNOT_PUSH,result().state()); }
    @Test void expirationDateIsAlreadyExpired() { response(member("30","active","\""+java.time.LocalDate.now(java.time.ZoneOffset.UTC)+"\"")); assertEquals(CANNOT_PUSH,result().state()); }
    @Test void futureExpirationStillAllows() { response(member("30","active","\"2099-01-01\"")); assertEquals(CAN_PUSH,result().state()); }
    @Test void missingExpirationCannotGrant() { response("{\"id\":900123,\"state\":\"active\",\"access_level\":30}"); assertEquals(UNKNOWN,result().state()); }
    @Test void malformedExpirationCannotGrant() { response(member("30","active","\"TEST-date\"")); assertEquals(UNKNOWN,result().state()); }
    @Test void overflowingAccessLevelCannotBecomeDeveloper() { response(member("4294967326","active","null")); assertEquals(UNKNOWN,result().state()); }
    @Test void failedReadCannotReuseEarlierPermission() {
        response(member("30","active","null")); assertEquals(CAN_PUSH,result().state());
        for (int status : new int[]{403,404,429,503}) { server.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status))); assertEquals(UNKNOWN,result().state()); }
    }
    @Test void malformedResponseCannotGrant() { response("{"); assertEquals(UNKNOWN,result().state()); }
}
