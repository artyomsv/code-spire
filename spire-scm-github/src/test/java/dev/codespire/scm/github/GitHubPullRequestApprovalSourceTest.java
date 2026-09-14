package dev.codespire.scm.github;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class GitHubPullRequestApprovalSourceTest {
    final ObjectMapper mapper=new ObjectMapper();
    final RepoRef repo=new RepoRef("TEST-owner","TEST-repo");
    final String path="/repos/TEST-owner/TEST-repo/pulls/42",head="b".repeat(40);
    WireMockServer api;GitHubPullRequestApprovalSource source;
    @BeforeEach void start() {
        api=new WireMockServer(WireMockConfiguration.options().dynamicPort());api.start();
        source=new GitHubPullRequestApprovalSource(new GitHubClient(new GitHubConfig(api.baseUrl(),"TEST-token","TEST-secret"),mapper));
        named(17,"900123");reviews("[{\"id\":17,\"state\":\"APPROVED\",\"user\":{\"id\":900123}}]");
        pull(42,false);
    }
    @AfterEach void stop(){api.stop();}
    void named(int id,String actor) {api.stubFor(get(urlEqualTo(path+"/reviews/17")).willReturn(okJson(
        "{\"id\":"+id+",\"state\":\"APPROVED\",\"commit_id\":\""+head+"\",\"user\":{\"id\":\""+actor+"\",\"type\":\"User\"}}")));}
    void reviews(String rows){api.stubFor(get(urlEqualTo(path+"/reviews?per_page=100&page=1")).willReturn(okJson(rows)));}
    void pull(int number,boolean merged){api.stubFor(get(urlEqualTo(path)).willReturn(okJson("{\"number\":"+number+",\"state\":\"open\",\"merged\":"+merged+"}")));}
    @Test void exactCurrentNativeApprovalIsAvailable(){var answer=source.read(repo,42,"17");assertTrue(source.available());assertTrue(answer.approved());assertTrue(answer.human());assertEquals(head,answer.head());}
    @Test void namedReviewIdentityMustMatch(){named(18,"900123");assertThrows(IllegalStateException.class,()->source.read(repo,42,"17"));}
    @Test void aDisplayNameCannotReplaceStableReviewActor(){named(17,"TEST-person");assertThrows(IllegalStateException.class,()->source.read(repo,42,"17"));}
    @Test void mergedPullCannotBeApproved(){pull(42,true);assertFalse(source.read(repo,42,"17").approved());}
    @Test void anotherPullCannotBeApproved(){pull(43,false);assertFalse(source.read(repo,42,"17").approved());}
    @Test void malformedHistoryCannotProveAnApproval(){reviews("{}");assertThrows(IllegalStateException.class,()->source.read(repo,42,"17"));}
    @Test void anotherActorsLatestReviewCannotDismissThisActorsApproval(){
        reviews("[{\"id\":17,\"state\":\"APPROVED\",\"user\":{\"id\":900123}},{\"id\":18,\"state\":\"CHANGES_REQUESTED\",\"user\":{\"id\":900456}}]");
        assertTrue(source.read(repo,42,"17").approved());
    }
    @Test void commentOnlyReviewDoesNotReplaceADecisiveApproval(){
        reviews("[{\"id\":17,\"state\":\"APPROVED\",\"user\":{\"id\":900123}},{\"id\":18,\"state\":\"COMMENTED\",\"user\":{\"id\":900123}}]");
        assertTrue(source.read(repo,42,"17").approved());
    }
    @Test void aFullLastPageCannotProveHistoryComplete(){
        var rows=mapper.createArrayNode();
        // Other people occupy the earlier rows; the named approval remains this actor's latest.
        for(int index=0;index<99;index++)rows.addObject().put("id",100+index).put("state","APPROVED").putObject("user").put("id",900456);
        rows.addObject().put("id",17).put("state","APPROVED").putObject("user").put("id",900123);
        api.stubFor(get(urlPathEqualTo(path+"/reviews")).willReturn(okJson(rows.toString())));
        assertFalse(source.read(repo,42,"17").approved());api.verify(20,getRequestedFor(urlPathEqualTo(path+"/reviews")));
    }
}
