package dev.codespire.worksource.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.context.github.GitHubIssueConfig;
import dev.codespire.worksource.*;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class GitHubWorkSourceTest {
    final ObjectMapper mapper = new ObjectMapper();
    WireMockServer api;
    GitHubWorkSource source;
    WorkIssueLocation issue;
    final String scope = "TEST-owner/TEST-repo";
    final String path = "/repos/TEST-owner/TEST-repo/issues/42";
    @BeforeEach void setup() {
        api = new WireMockServer(WireMockConfiguration.options().dynamicPort()); api.start();
        api.stubFor(get(urlEqualTo("/repos/" + scope)).willReturn(okJson("{\"id\":10001,\"full_name\":\"" + scope + "\",\"html_url\":\"" + api.baseUrl() + "/" + scope + "\"}")));
        source = new GitHubWorkSource(config(), mapper, "10001", scope);
        issue = new WorkIssueLocation(new WorkIssueRef(WorkSourceType.GITHUB, api.baseUrl(), "10001", "50001"), "42", URI.create(api.baseUrl() + "/" + scope + "/issues/42"));
        api.stubFor(get(urlEqualTo(path)).willReturn(okJson(ticket().toString())));
        timeline(1, mapper.createArrayNode().add(event(101,"labeled",900123,0)), null);
    }
    @AfterEach void stop() { api.stop(); }
    GitHubIssueConfig config() { return new GitHubIssueConfig(api.baseUrl(),"bearer","TEST-token",Set.of(scope)); }
    ObjectNode ticket() {
        ObjectNode node = mapper.createObjectNode().put("id",50001).put("number",42)
                .put("repository_url",api.baseUrl()+"/repos/"+scope).put("title","TEST-ticket")
                .put("body","TEST-body").put("state","open").put("updated_at","2026-09-13T12:00:00Z");
        node.putArray("labels").addObject().put("name","TEST-suggest"); return node;
    }
    ObjectNode event(int id,String kind,int actor,int second) {
        ObjectNode node=mapper.createObjectNode().put("id",id).put("event",kind)
                .put("created_at",String.format("2026-09-13T12:00:%02dZ",second));
        node.putObject("actor").put("id",actor); node.putObject("label").put("name","TEST-suggest"); return node;
    }
    void timeline(int page,ArrayNode events,String next) {
        var response=okJson(events.toString()); if(next!=null)response.withHeader("Link","<"+next+">; rel=\"next\"");
        api.stubFor(get(urlEqualTo(path+"/timeline?per_page=100&page="+page)).willReturn(response));
    }

    @Test void dotPrefixedRepositoryNamesRemainValidScopes() {
        String name = "TEST-owner/.github";
        api.stubFor(get(urlEqualTo("/repos/" + name)).willReturn(okJson("{\"id\":10002,\"full_name\":\"" + name
                + "\",\"html_url\":\"" + api.baseUrl() + "/" + name + "\"}")));
        GitHubWorkSource.Scope resolved = assertDoesNotThrow(() -> GitHubWorkSource.resolveScope(config(), mapper, name));
        assertEquals("10002", resolved.projectId());
        assertEquals(name, resolved.name());
    }

    @Test void reconstructsCurrentLabelApplierAcrossPages() {
        timeline(1,mapper.createArrayNode().add(event(101,"labeled",900123,0)).add(event(102,"unlabeled",900456,1)),
                api.baseUrl()+path+"/timeline?per_page=100&page=2");
        timeline(2,mapper.createArrayNode().add(event(103,"labeled",900789,2)),null);
        WorkPage<LabelEvent> first=source.labelEvents(issue,null);
        assertEquals("2",first.nextCursor());
        WorkPage<LabelEvent> second=source.labelEvents(issue,first.nextCursor());
        assertNull(second.nextCursor());
        List<LabelEvent> history=new ArrayList<>(first.items());history.addAll(second.items());
        CurrentLabel current=LabelReconciler.reconcile(issue.ref(),Set.of("TEST-suggest"),history,true).getFirst();
        assertEquals("900789",current.trackerActorId());assertEquals(CurrentLabel.Reason.ATTRIBUTED,current.reason());
        assertEquals(LabelEvent.Action.REMOVE,first.items().get(1).action());
    }
    @Test void anAuditFailureIsNotAnEmptyCompletePage() {
        api.stubFor(get(urlEqualTo(path+"/timeline?per_page=100&page=1")).willReturn(aResponse().withStatus(403)));
        assertThrows(WorkSourceException.class,()->source.labelEvents(issue,null));
    }
    @Test void candidateListsExcludePullRequests() {
        ObjectNode pr=ticket();pr.put("id",50002);pr.put("number",43);pr.putObject("pull_request");
        api.stubFor(get(urlEqualTo("/repos/"+scope+"/issues?state=open&sort=created&direction=asc&per_page=100&page=1"))
                .willReturn(okJson(mapper.createArrayNode().add(pr).add(ticket()).toString())));
        WorkPage<WorkIssueLocation> page=source.candidates(null);
        assertEquals(List.of(issue),page.items());assertNull(page.nextCursor());
    }
    @Test void aPullRequestCannotBeFetchedAsAWorkTicket() {
        ObjectNode pr=ticket();pr.putObject("pull_request");api.stubFor(get(urlEqualTo(path)).willReturn(okJson(pr.toString())));
        assertInstanceOf(WorkSource.Fetch.Unavailable.class,source.fetch(issue));
    }
    @Test void aHiddenIssueIsUnavailableNotDeleted() {
        api.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(404)));
        assertInstanceOf(WorkSource.Fetch.Unavailable.class,source.fetch(issue));
    }
    @Test void malformedLabelsCannotBecomeAnEmptyCurrentSet() {
        ObjectNode malformed=ticket();malformed.set("labels",mapper.createObjectNode());
        api.stubFor(get(urlEqualTo(path)).willReturn(okJson(malformed.toString())));
        assertInstanceOf(WorkSource.Fetch.Unavailable.class,source.fetch(issue));
    }
    @Test void aBlankCurrentLabelIsUnavailable() {
        ObjectNode malformed=ticket();malformed.putArray("labels").addObject().put("name","");
        api.stubFor(get(urlEqualTo(path)).willReturn(okJson(malformed.toString())));
        assertInstanceOf(WorkSource.Fetch.Unavailable.class,source.fetch(issue));
    }
    @Test void anAuditForAnotherProjectIsRefusedBeforeReading() {
        WorkIssueLocation other=new WorkIssueLocation(new WorkIssueRef(WorkSourceType.GITHUB,api.baseUrl(),"99999","50001"),"42",issue.link());
        assertThrows(WorkSourceException.class,()->source.labelEvents(other,null));
        api.verify(0,getRequestedFor(urlPathEqualTo(path+"/timeline")));
    }
    @Test void anAuditForAnotherOriginIsRefusedBeforeReading() {
        WorkIssueLocation other=new WorkIssueLocation(new WorkIssueRef(WorkSourceType.GITHUB,"https://foreign.example.test","10001","50001"),"42",issue.link());
        assertThrows(WorkSourceException.class,()->source.labelEvents(other,null));
        api.verify(0,getRequestedFor(urlPathEqualTo(path+"/timeline")));
    }
    @Test void aForeignPaginationLinkCannotEstablishCompleteEvidence() {
        timeline(1,mapper.createArrayNode().add(event(101,"labeled",900123,0)),"https://foreign.example.test"+path+"/timeline?per_page=100&page=2");
        assertThrows(WorkSourceException.class,()->source.labelEvents(issue,null));
    }
    @Test void aPaginationGapCannotEstablishCompleteEvidence() {
        timeline(1,mapper.createArrayNode().add(event(101,"labeled",900123,0)),api.baseUrl()+path+"/timeline?per_page=100&page=3");
        assertThrows(WorkSourceException.class,()->source.labelEvents(issue,null));
    }
    @Test void anUnrelatedTimelineEventCannotBecomeALabel() {
        ObjectNode renamed=event(102,"renamed",900456,1);
        timeline(1,mapper.createArrayNode().add(event(101,"labeled",900123,0)).add(renamed),null);
        assertEquals(1,source.labelEvents(issue,null).items().size());
    }
    @Test void aMissingActorRemainsMissing() {
        ObjectNode missing=event(101,"labeled",900123,0);missing.putNull("actor");timeline(1,mapper.createArrayNode().add(missing),null);
        assertNull(source.labelEvents(issue,null).items().getFirst().trackerActorId());
    }
    @Test void writesUseTheSeparateFacadeAndTheSelectedCredential() {
        api.stubFor(post(urlEqualTo(path+"/comments")).willReturn(okJson("{\"id\":70001}")));
        assertEquals("70001",source.comment(issue,"TEST-comment","TEST-effect"));
        api.verify(postRequestedFor(urlEqualTo(path+"/comments")).withHeader("Authorization",equalTo("Bearer TEST-token"))
                .withHeader("X-GitHub-Api-Version",equalTo("2022-11-28"))
                .withRequestBody(containing("work-effect:TEST-effect")));
        api.stubFor(patch(urlEqualTo(path)).willReturn(okJson(ticket().put("state","closed").toString())));
        source.transition(issue,"closed","TEST-transition");
        api.verify(patchRequestedFor(urlEqualTo(path)).withRequestBody(equalToJson("{\"state\":\"closed\"}")));
    }
    @Test void unsupportedTransitionsCannotBecomeTrackerWrites() {
        api.stubFor(patch(urlEqualTo(path)).willReturn(okJson(ticket().put("state","TEST-unknown").toString())));
        assertThrows(WorkSourceException.class,()->source.transition(issue,"TEST-unknown","TEST-effect"));
        api.verify(0,patchRequestedFor(urlEqualTo(path)));
    }
    @Test void anUnavailableTicketCannotReceiveAComment() {
        api.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(403)));
        api.stubFor(post(urlEqualTo(path+"/comments")).willReturn(okJson("{\"id\":70001}")));
        assertThrows(WorkSourceException.class,()->source.comment(issue,"TEST-comment","TEST-effect"));
        api.verify(0,postRequestedFor(urlEqualTo(path+"/comments")));
    }
    @Test void aCommentNeedsAStableAcknowledgement() {
        api.stubFor(post(urlEqualTo(path+"/comments")).willReturn(okJson("{}")));
        assertThrows(WorkSourceException.class,()->source.comment(issue,"TEST-comment","TEST-effect"));
    }
    @Test void aTransitionNeedsTheRequestedStateInItsAcknowledgement() {
        api.stubFor(patch(urlEqualTo(path)).willReturn(okJson(ticket().put("state","open").toString())));
        assertThrows(WorkSourceException.class,()->source.transition(issue,"closed","TEST-effect"));
    }
    @Test void aTransitionNeedsTheSameIssueInItsAcknowledgement() {
        api.stubFor(patch(urlEqualTo(path)).willReturn(okJson(ticket().put("state","closed").put("id",99999).toString())));
        assertThrows(WorkSourceException.class,()->source.transition(issue,"closed","TEST-effect"));
    }
    @Test void repositoryMetadataMustMatchTheSelectedScope() {
        api.stubFor(get(urlEqualTo("/repos/"+scope)).willReturn(okJson("{\"id\":10001,\"full_name\":\"TEST-other/TEST-repo\",\"html_url\":\""+api.baseUrl()+"/"+scope+"\"}")));
        assertThrows(WorkSourceException.class,()->new GitHubWorkSource(config(),mapper,"10001",scope));
    }
    @Test void repositoryMetadataMustMatchTheStableProject() {assertThrows(WorkSourceException.class,()->new GitHubWorkSource(config(),mapper,"10002",scope));}
    @Test void metadataCannotIntroduceACredentialBearingLink() {
        api.stubFor(get(urlEqualTo("/repos/"+scope)).willReturn(okJson("{\"id\":10001,\"full_name\":\""+scope+"\",\"html_url\":\"https://TEST-user:TEST-secret@tracker.example.test/TEST-repo\"}")));
        assertThrows(WorkSourceException.class,()->new GitHubWorkSource(config(),mapper,"10001",scope));
    }
    @Test void candidateResponseMustBeAnArray() {
        api.stubFor(get(urlEqualTo("/repos/"+scope+"/issues?state=open&sort=created&direction=asc&per_page=100&page=1"))
                .willReturn(okJson(mapper.createObjectNode().set("TEST-first",ticket()).toString())));
        assertThrows(WorkSourceException.class,()->source.candidates(null));
    }
    @Test void auditResponseMustBeAnArray() {
        api.stubFor(get(urlEqualTo(path+"/timeline?per_page=100&page=1"))
                .willReturn(okJson(mapper.createObjectNode().set("TEST-first",event(101,"labeled",900123,0)).toString())));
        assertThrows(WorkSourceException.class,()->source.labelEvents(issue,null));
    }
    @Test void aFetchedTicketCannotChangeItsStableId() {
        api.stubFor(get(urlEqualTo(path)).willReturn(okJson(ticket().put("id",50002).toString())));
        assertInstanceOf(WorkSource.Fetch.Unavailable.class,source.fetch(issue));
    }
    @Test void aFetchedTicketCannotBelongToAnotherRepository() {
        api.stubFor(get(urlEqualTo(path)).willReturn(okJson(ticket().put("repository_url",api.baseUrl()+"/repos/TEST-other/TEST-repo").toString())));
        assertInstanceOf(WorkSource.Fetch.Unavailable.class,source.fetch(issue));
    }
    @Test void blankAuditLabelsDoNotEstablishACompleteHistory() {
        ObjectNode malformed=event(101,"labeled",900123,0);malformed.putObject("label").put("name","");
        timeline(1,mapper.createArrayNode().add(malformed),null);assertThrows(WorkSourceException.class,()->source.labelEvents(issue,null));
    }
    @Test void aForeignSourceTypeCannotBorrowTheSameIds() {
        WorkIssueLocation other=new WorkIssueLocation(new WorkIssueRef(WorkSourceType.JIRA,api.baseUrl(),"10001","50001"),"42",issue.link());
        assertThrows(WorkSourceException.class,()->source.labelEvents(other,null));
    }
    @Test void aPaginationLinkCannotChangeRepositoryPath() {
        timeline(1,mapper.createArrayNode().add(event(101,"labeled",900123,0)),api.baseUrl()+"/repos/TEST-other/TEST-repo/issues/42/timeline?page=2");
        assertThrows(WorkSourceException.class,()->source.labelEvents(issue,null));
    }
    @Test void duplicateNextLinksCannotEstablishCompleteEvidence() {
        String link="<"+api.baseUrl()+path+"/timeline?page=2>; rel=\"next\"";
        api.stubFor(get(urlEqualTo(path+"/timeline?per_page=100&page=1")).willReturn(okJson("[]").withHeader("Link",link+", "+link)));
        assertThrows(WorkSourceException.class,()->source.labelEvents(issue,null));
    }
    @Test void duplicatePageNumbersCannotEstablishCompleteEvidence() {
        timeline(1,mapper.createArrayNode().add(event(101,"labeled",900123,0)),api.baseUrl()+path+"/timeline?page=2&page=2");
        assertThrows(WorkSourceException.class,()->source.labelEvents(issue,null));
    }
    @Test void paginationFragmentsCannotEstablishCompleteEvidence() {
        timeline(1,mapper.createArrayNode().add(event(101,"labeled",900123,0)),api.baseUrl()+path+"/timeline?page=2#TEST-fragment");
        assertThrows(WorkSourceException.class,()->source.labelEvents(issue,null));
    }
    @Test void noncanonicalPageNumbersAreRejected() {
        api.stubFor(get(urlEqualTo(path+"/timeline?per_page=100&page=2")).willReturn(okJson("[]")));
        assertThrows(WorkSourceException.class,()->source.labelEvents(issue,"02"));
    }
    @Test void zeroPageNumbersAreRejected() {
        api.stubFor(get(urlEqualTo(path+"/timeline?per_page=100&page=0")).willReturn(okJson("[]")));
        assertThrows(WorkSourceException.class,()->source.labelEvents(issue,"0"));
    }
    @Test void floatingPointIdsAreNotStableIdentities() {
        WorkIssueLocation floating=new WorkIssueLocation(new WorkIssueRef(WorkSourceType.GITHUB,api.baseUrl(),"10001","50001.0"),"42",issue.link());
        api.stubFor(get(urlEqualTo(path)).willReturn(okJson(ticket().put("id",50001.0).toString())));
        assertInstanceOf(WorkSource.Fetch.Unavailable.class,source.fetch(floating));
    }
    @Test void negativeIdsAreNotStableIdentities() {
        WorkIssueLocation negative=new WorkIssueLocation(new WorkIssueRef(WorkSourceType.GITHUB,api.baseUrl(),"10001","-1"),"42",issue.link());
        api.stubFor(get(urlEqualTo(path)).willReturn(okJson(ticket().put("id",-1).toString())));
        assertInstanceOf(WorkSource.Fetch.Unavailable.class,source.fetch(negative));
    }
    @Test void overflowingIdsAreNotStableIdentities() {
        String large="18446744073709601617";
        WorkIssueLocation overflow=new WorkIssueLocation(new WorkIssueRef(WorkSourceType.GITHUB,api.baseUrl(),"10001",large),"42",issue.link());
        api.stubFor(get(urlEqualTo(path)).willReturn(okJson(ticket().put("id",new java.math.BigInteger(large)).toString())));
        assertInstanceOf(WorkSource.Fetch.Unavailable.class,source.fetch(overflow));
    }
}
