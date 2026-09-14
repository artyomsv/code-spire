package dev.codespire.scm.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.RepositoryPermission;
import org.junit.jupiter.api.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;
import static dev.codespire.contract.scm.RepositoryPermission.State.*;

class GitHubRepositoryPermissionSourceTest {
    static WireMockServer server;
    GitHubRepositoryPermissionSource source;
    final RepoRef repo = new RepoRef("TEST-owner", "TEST-repo");
    @BeforeAll static void start() { server = new WireMockServer(WireMockConfiguration.options().dynamicPort()); server.start(); }
    @AfterAll static void stop() { server.stop(); }
    @BeforeEach void reset() { server.resetAll(); source = new GitHubRepositoryPermissionSource(new GitHubClient(new GitHubConfig(server.baseUrl(), "TEST-token", null), new ObjectMapper())); stub("/user/900123", "{\"id\":900123,\"login\":\"TEST-renamed\"}"); }
    void stub(String path, String json) { server.stubFor(get(urlEqualTo(path)).willReturn(okJson(json))); }
    RepositoryPermission result() { return source.permission(repo, "900123"); }
    static final String PATH = "/repos/TEST-owner/TEST-repo/collaborators/TEST-renamed/permission";
    void response(String json) { stub(PATH, json); }
    @Test void inheritedWriteAccessIsRecognized() {
        response("{\"permission\":\"write\",\"role_name\":\"maintain\",\"user\":{\"id\":900123}}");
        assertEquals(CAN_PUSH, result().state());
        server.verify(getRequestedFor(urlEqualTo(PATH)).withHeader("Authorization", equalTo("Bearer TEST-token")));
    }
    @Test void unknownResponseCannotGrant() { response("{\"permission\":\"TEST-custom\",\"role_name\":\"admin\",\"user\":{\"id\":900123}}"); assertEquals(UNKNOWN, result().state()); }
    @Test void readerCannotPush() { response("{\"permission\":\"read\",\"user\":{\"id\":900123}}"); assertEquals(CANNOT_PUSH,result().state()); }
    @Test void differentReturnedIdentityCannotGrant() { response("{\"permission\":\"admin\",\"user\":{\"id\":900999}}"); assertEquals(UNKNOWN,result().state()); }
    @Test void missingIdentityCannotGrant() { stub("/user/900123","{}"); assertEquals(UNKNOWN,result().state()); server.verify(0,getRequestedFor(urlEqualTo(PATH))); }
    @Test void failedReadCannotReuseEarlierPermission() {
        response("{\"permission\":\"write\",\"user\":{\"id\":900123}}"); assertEquals(CAN_PUSH,result().state());
        for (int status : new int[]{403,404,429,503}) {
            server.stubFor(get(urlEqualTo(PATH)).willReturn(aResponse().withStatus(status).withBody("TEST-secret")));
            assertEquals(UNKNOWN,result().state()); assertFalse(result().detail().contains("TEST-secret"));
        }
    }
    @Test void malformedResponseCannotGrant() { response("{"); assertEquals(UNKNOWN,result().state()); }
}
