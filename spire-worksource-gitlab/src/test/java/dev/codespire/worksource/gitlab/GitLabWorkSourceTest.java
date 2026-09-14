package dev.codespire.worksource.gitlab;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.context.gitlab.GitLabIssueConfig;
import dev.codespire.worksource.*;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class GitLabWorkSourceTest {
    final ObjectMapper mapper = new ObjectMapper();
    WireMockServer api;
    GitLabWorkSource source;
    WorkIssueLocation issue;
    final String scope = "TEST-group/TEST-subgroup/TEST-repo", path = "/api/v4/projects/10001/issues/42";
    @BeforeEach void start() {
        api = new WireMockServer(WireMockConfiguration.options().dynamicPort()); api.start();
        api.stubFor(get(urlEqualTo("/api/v4/projects/TEST-group%2FTEST-subgroup%2FTEST-repo"))
                .willReturn(okJson("{\"id\":10001,\"path_with_namespace\":\"" + scope + "\",\"web_url\":\"" + api.baseUrl() + "/" + scope + "\"}")));
        source = new GitLabWorkSource(config(), mapper, "10001", scope);
        issue = new WorkIssueLocation(new WorkIssueRef(WorkSourceType.GITLAB, api.baseUrl(), "10001", "50001"), "42", URI.create(api.baseUrl() + "/" + scope + "/-/issues/42"));
        api.stubFor(get(urlEqualTo(path)).willReturn(okJson(ticket().toString())));
        audit(1, List.of(event(101,"add",900123,0)), "");
        api.stubFor(get(urlEqualTo(path + "/notes?sort=asc&order_by=created_at&per_page=100&page=1")).willReturn(okJson("[]").withHeader("X-Next-Page", "")));
    }
    @AfterEach void stop() { api.stop(); }
    GitLabIssueConfig config() { return new GitLabIssueConfig(api.baseUrl(),"bearer","TEST-token",Set.of(scope)); }
    ObjectNode ticket() {
        ObjectNode node = mapper.createObjectNode().put("id",50001).put("iid",42).put("project_id",10001)
                .put("title","TEST-ticket").put("description","TEST-body").put("state","opened");
        node.putArray("labels").add("TEST-auto"); return node;
    }
    ObjectNode event(int id,String action,int actor,int second) {
        ObjectNode node = mapper.createObjectNode().put("id",id).put("resource_type","Issue").put("resource_id",50001)
                .put("action",action).put("created_at",String.format("2026-09-13T12:00:%02dZ",second));
        node.putObject("user").put("id",actor); node.putObject("label").put("name","TEST-auto"); return node;
    }
    void audit(int page, List<ObjectNode> events, String next) {
        var response=okJson(mapper.valueToTree(events).toString()); if(next!=null)response.withHeader("X-Next-Page",next);
        api.stubFor(get(urlEqualTo(path+"/resource_label_events?per_page=100&page="+page)).willReturn(response));
    }
    @Test void reconstructsCurrentLabelApplierAcrossPages() {
        audit(1,List.of(event(101,"add",900123,0),event(102,"remove",900123,1)),"2");
        audit(2,List.of(event(103,"add",900456,2)),"");
        var first=source.labelEvents(issue,null); var second=source.labelEvents(issue,first.nextCursor());
        assertEquals("2",first.nextCursor()); assertNull(second.nextCursor());
        List<LabelEvent> events=new ArrayList<>(first.items()); events.addAll(second.items());
        assertEquals(LabelEvent.Action.REMOVE,first.items().get(1).action());
        assertEquals("900456",LabelReconciler.reconcile(issue.ref(),Set.of("TEST-auto"),events,true).getFirst().trackerActorId());
    }
    @Test void pollingFetchesCanonicalTicketThroughTheSelectedAccount() {
        api.stubFor(get(urlEqualTo("/api/v4/projects/10001/issues?state=all&scope=all&order_by=created_at&sort=asc&per_page=100&page=1"))
                .willReturn(okJson(mapper.createArrayNode().add(ticket()).toString()).withHeader("X-Next-Page","")));
        var page=source.candidates(null); assertEquals(List.of(issue),page.items()); assertNull(page.nextCursor());
        var found=assertInstanceOf(WorkSource.Fetch.Found.class,source.fetch(page.items().getFirst()));
        assertEquals("TEST-ticket",found.ticket().title()); assertEquals(Set.of("TEST-auto"),found.ticket().currentLabels());
        api.verify(getRequestedFor(urlEqualTo(path)).withHeader("Authorization",equalTo("Bearer TEST-token")));
    }
    @ParameterizedTest @ValueSource(ints={401,403,404,410,503}) void inaccessibleIsNeverDeleted(int status) {
        api.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(status)));
        assertInstanceOf(WorkSource.Fetch.Unavailable.class,source.fetch(issue));
    }
    @Test void anAuditErrorIsNotACompleteEmptyPage() {
        api.stubFor(get(urlEqualTo(path+"/resource_label_events?per_page=100&page=1")).willReturn(aResponse().withStatus(503)));
        assertThrows(RuntimeException.class,()->source.labelEvents(issue,null));
    }
    @Test void missingUserCannotBorrowTheIssueAuthor() {
        ObjectNode event=event(101,"add",900123,0); event.putNull("user"); audit(1,List.of(event),"");
        var current=LabelReconciler.reconcile(issue.ref(),Set.of("TEST-auto"),source.labelEvents(issue,null).items(),true).getFirst();
        assertNull(current.trackerActorId()); assertEquals(LabelEvent.Origin.UNATTRIBUTED,current.origin());
    }
    @Test void aDifferentProjectCannotSupplyTicketContent() {
        api.stubFor(get(urlEqualTo(path)).willReturn(okJson(ticket().put("project_id",10002).toString())));
        assertInstanceOf(WorkSource.Fetch.Unavailable.class,source.fetch(issue));
    }
    @Test void aDifferentStableIssueCannotSupplyTicketContent() {
        api.stubFor(get(urlEqualTo(path)).willReturn(okJson(ticket().put("id",50002).toString())));
        assertInstanceOf(WorkSource.Fetch.Unavailable.class,source.fetch(issue));
    }
    @Test void anotherIssueCannotSupplyLabelEvidence() { audit(1,List.of(event(101,"add",900123,0).put("resource_id",50002)),""); assertThrows(RuntimeException.class,()->source.labelEvents(issue,null)); }
    @Test void anotherResourceTypeCannotSupplyLabelEvidence() { audit(1,List.of(event(101,"add",900123,0).put("resource_type","MergeRequest")),""); assertThrows(RuntimeException.class,()->source.labelEvents(issue,null)); }
    @ParameterizedTest @ValueSource(strings={"0","01","-1","../42",""}) void rejectsNoncanonicalCoordinates(String key) {
        var other=new WorkIssueLocation(issue.ref(),key,issue.link()); assertInstanceOf(WorkSource.Fetch.Unavailable.class,source.fetch(other));
        api.verify(0,getRequestedFor(urlEqualTo(path)));
    }
    @ParameterizedTest @ValueSource(strings={"1","3","02","-1"}) void refusesPaginationGapsCyclesAndAliases(String next) { audit(1,List.of(event(101,"add",900123,0)),next); assertThrows(RuntimeException.class,()->source.labelEvents(issue,null)); }
    @Test void cannotClaimAFullPageWithoutCompletionEvidence() {
        audit(1,Collections.nCopies(100,event(101,"add",900123,0)),null); assertThrows(RuntimeException.class,()->source.labelEvents(issue,null));
    }
    @Test void commentRecoveryFindsAnExistingMarkerWithoutPosting() {
        api.stubFor(post(urlEqualTo(path+"/notes")).willReturn(okJson("{\"id\":70002}")));
        String body=WorkEffectMarker.body("TEST-text","TEST-effect");
        api.stubFor(get(urlEqualTo(path+"/notes?sort=asc&order_by=created_at&per_page=100&page=1"))
                .willReturn(okJson(mapper.createArrayNode().add(mapper.createObjectNode().put("id",70001).put("body",body)).toString()).withHeader("X-Next-Page","")));
        assertEquals("70001",source.comment(issue,"TEST-text","TEST-effect")); api.verify(0,postRequestedFor(urlEqualTo(path+"/notes")));
    }
    @Test void commentUsesTheSeparateAuthenticatedWriter() {
        api.stubFor(post(urlEqualTo(path+"/notes")).willReturn(okJson("{\"id\":70001}")));
        assertEquals("70001",source.comment(issue,"TEST-text","TEST-effect"));
        api.verify(postRequestedFor(urlEqualTo(path+"/notes")).withHeader("Authorization",equalTo("Bearer TEST-token"))
                .withRequestBody(containing("<!-- work-effect:TEST-effect -->")));
    }
    @Test void closingUsesTheRealStateEventAndChecksAcknowledgement() {
        api.stubFor(put(urlEqualTo(path)).willReturn(okJson("{\"id\":50001,\"state\":\"closed\"}")));
        source.transition(issue,"close","TEST-effect");
        api.verify(putRequestedFor(urlEqualTo(path)).withRequestBody(equalToJson("{\"state_event\":\"close\"}")));
    }
    @Test void aCompletedTransitionIsNotRepeated() {
        api.stubFor(put(urlEqualTo(path)).willReturn(okJson("{\"id\":50001,\"state\":\"closed\"}")));
        api.stubFor(get(urlEqualTo(path)).willReturn(okJson(ticket().put("state","closed").toString())));
        source.transition(issue,"close","TEST-effect"); api.verify(0,putRequestedFor(urlEqualTo(path)));
    }
    @Test void arbitraryTransitionNamesAreRefused() { assertThrows(RuntimeException.class,()->source.transition(issue,"TEST-approve","TEST-effect")); api.verify(0,putRequestedFor(urlEqualTo(path))); }
    @Test void malformedTransitionAcknowledgementRemainsUncertain() {
        api.stubFor(put(urlEqualTo(path)).willReturn(okJson("{\"id\":50002,\"state\":\"closed\"}")));
        assertThrows(RuntimeException.class,()->source.transition(issue,"close","TEST-effect"));
    }
    @Test void wrongTransitionStateRemainsUncertain() {
        api.stubFor(put(urlEqualTo(path)).willReturn(okJson("{\"id\":50001,\"state\":\"opened\"}")));
        assertThrows(WorkSourceException.class,()->source.transition(issue,"close","TEST-effect"));
    }
    @Test void unknownLabelActionCannotBecomeRemoval() {
        audit(1,List.of(event(101,"TEST-unknown",900123,0)),"");assertThrows(WorkSourceException.class,()->source.labelEvents(issue,null));
    }
    @Test void emptyLabelCannotBecomeEvidence() {
        var event=event(101,"add",900123,0);((ObjectNode)event.path("label")).put("name","");audit(1,List.of(event),"");
        assertThrows(WorkSourceException.class,()->source.labelEvents(issue,null));
    }
    @Test void aPaginationCycleIsRefused() { audit(1,List.of(event(101,"add",900123,0)),"1");assertThrows(WorkSourceException.class,()->source.labelEvents(issue,null)); }
    @Test void paginationCannotLeaveTheOrigin() {
        api.stubFor(get(urlPathEqualTo(path+"/resource_label_events")).willReturn(okJson("[]").withHeader("Link","<https://TEST-other.invalid"+path+"/resource_label_events?page=2>; rel=\"next\"")));
        assertThrows(WorkSourceException.class,()->source.labelEvents(issue,null));
    }
    @Test void paginationCannotLeaveTheIssuePath() {
        api.stubFor(get(urlPathEqualTo(path+"/resource_label_events")).willReturn(okJson("[]").withHeader("Link","<"+api.baseUrl()+"/api/v4/projects/10002/issues/42/resource_label_events?page=2>; rel=\"next\"")));
        assertThrows(WorkSourceException.class,()->source.labelEvents(issue,null));
    }
    @Test void paginationHeadersMustAgree() {
        api.stubFor(get(urlPathEqualTo(path+"/resource_label_events")).willReturn(okJson("[]").withHeader("X-Next-Page","2")
                .withHeader("Link","<"+api.baseUrl()+path+"/resource_label_events?page=3>; rel=\"next\"")));
        assertThrows(WorkSourceException.class,()->source.labelEvents(issue,null));
    }
    @Test void sourceIdentityMustMatchTheResolvedProject() { assertThrows(WorkSourceException.class,()->new GitLabWorkSource(config(),mapper,"10002",scope)); }
    @Test void scopeResponseMustMatchTheRequestedProject() {
        api.stubFor(get(urlPathEqualTo("/api/v4/projects/TEST-group%2FTEST-subgroup%2FTEST-repo")).willReturn(okJson("{\"id\":10001,\"path_with_namespace\":\"TEST-other/TEST-repo\",\"web_url\":\""+api.baseUrl()+"/TEST-other/TEST-repo\"}")));
        assertThrows(WorkSourceException.class,()->new GitLabWorkSource(config(),mapper,"10001",scope));
    }
    @Test void auditErrorsDoNotExposeTrackerBodies() {
        api.stubFor(get(urlPathEqualTo(path+"/resource_label_events")).willReturn(aResponse().withStatus(403).withBody("TEST-private-error")));
        var error=assertThrows(WorkSourceException.class,()->source.labelEvents(issue,null));assertFalse(error.toString().contains("TEST-private-error"));assertNull(error.getCause());
    }
    @Test void writeErrorsDoNotExposeTrackerBodies() {
        api.stubFor(post(urlEqualTo(path+"/notes")).willReturn(aResponse().withStatus(403).withBody("TEST-private-error")));
        var error=assertThrows(WorkSourceException.class,()->source.comment(issue,"TEST-text","TEST-effect"));assertFalse(error.toString().contains("TEST-private-error"));assertNull(error.getCause());
    }
    @Test void auditCannotUseAnotherSourceType() {
        var other=new WorkIssueLocation(new WorkIssueRef(WorkSourceType.JIRA,api.baseUrl(),"10001","50001"),"42",issue.link());
        assertThrows(WorkSourceException.class,()->source.labelEvents(other,null));
    }
    @Test void auditCannotUseAnotherOrigin() {
        var other=new WorkIssueLocation(new WorkIssueRef(WorkSourceType.GITLAB,"https://TEST-other.invalid","10001","50001"),"42",issue.link());
        assertThrows(WorkSourceException.class,()->source.labelEvents(other,null));
    }
    @Test void auditCannotUseAnotherProject() {
        var other=new WorkIssueLocation(new WorkIssueRef(WorkSourceType.GITLAB,api.baseUrl(),"10002","50001"),"42",issue.link());
        assertThrows(WorkSourceException.class,()->source.labelEvents(other,null));
    }
    @Test void anAliasPageCannotMasqueradeAsPageOne() { assertThrows(WorkSourceException.class,()->source.labelEvents(issue,"01")); }
    @Test void auditIdentityMustBePositive() { audit(1,List.of(event(0,"add",900123,0)),"");assertThrows(WorkSourceException.class,()->source.labelEvents(issue,null)); }
    @Test void duplicatedNextHeadersCannotChooseAnArbitraryCompletion() {
        api.stubFor(get(urlPathEqualTo(path+"/resource_label_events")).willReturn(okJson("[]").withHeader("X-Next-Page","2","3")));
        assertThrows(WorkSourceException.class,()->source.labelEvents(issue,null));
    }
    @Test void incompleteCommentSearchCannotAuthorizeAPost() {
        for(int page=1;page<=21;page++)api.stubFor(get(urlEqualTo(path+"/notes?sort=asc&order_by=created_at&per_page=100&page="+page))
                .willReturn(okJson("[]").withHeader("X-Next-Page",page==21?"":Integer.toString(page+1))));
        api.stubFor(post(urlEqualTo(path+"/notes")).willReturn(okJson("{\"id\":70001}")));
        assertThrows(WorkSourceException.class,()->source.comment(issue,"TEST-text","TEST-effect"));
        api.verify(0,postRequestedFor(urlEqualTo(path+"/notes")));
    }
}
