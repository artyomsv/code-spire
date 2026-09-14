package dev.codespire.worksource.jira;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.context.jira.JiraConfig;
import dev.codespire.worksource.*;
import java.net.URI;
import java.util.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

class JiraWorkSourceTest {
    final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules();
    WireMockServer api;
    JiraWorkSource source;
    WorkIssueLocation issue;
    final String path = "/rest/api/2/issue/50001";
    @BeforeEach void start() {
        api = new WireMockServer(WireMockConfiguration.options().dynamicPort()); api.start();
        api.stubFor(get(urlEqualTo("/rest/api/2/project/TEST")).willReturn(okJson("{\"id\":\"10001\",\"key\":\"TEST\"}")));
        api.stubFor(get(urlEqualTo("/rest/api/2/serverInfo")).willReturn(okJson("{\"deploymentType\":\"Cloud\"}")));
        source = new JiraWorkSource(config(), mapper, "10001", "TEST");
        issue = new WorkIssueLocation(new WorkIssueRef(WorkSourceType.JIRA, api.baseUrl(), "10001", "50001"), "TEST-42", URI.create(api.baseUrl()+"/browse/TEST-42"));
        fetched(ticket());
        audit(0, List.of(history(101,"TEST-old","TEST-old TEST-auto","900123")),1);
        api.stubFor(get(urlEqualTo(path+"/comment?startAt=0&maxResults=100&orderBy=created"))
                .willReturn(okJson(page("comments",0,0,List.of()).toString())));
        api.stubFor(get(urlEqualTo(path+"/transitions?expand=transitions.fields")).willReturn(okJson("{\"transitions\":[{\"id\":\"31\",\"name\":\"TEST-complete\",\"to\":{\"id\":\"10003\"},\"fields\":{}}]}")));
    }
    @AfterEach void stop() { api.stop(); }
    @Test void pollsExplicitAnswersByAccountIdWithoutPersistingCommentProse() {
        UUID gate=UUID.randomUUID();
        var row=mapper.createObjectNode().put("id","17").put("created","2026-09-14T12:00:00.000+0000")
                .put("body","/approve "+gate+" 3 "+"a".repeat(64));
        row.putObject("author").put("accountId","900123").put("displayName","TEST-renamed-person");
        api.stubFor(get(urlEqualTo(path+"/comment?startAt=0&maxResults=100&orderBy=created"))
                .willReturn(okJson(page("comments",0,1,List.of(row)).toString())));
        var page=source.activities(issue,null);assertTrue(source.pollsActivities());assertNull(page.nextCursor());
        var answer=page.items().getFirst();assertEquals("900123",answer.actorId());assertEquals(gate,answer.answer().gateId());
        assertEquals(java.time.Instant.parse("2026-09-14T12:00:00Z"),answer.occurredAt());
        assertFalse(mapper.valueToTree(answer).has("body"));
    }
    @Test void ordinaryJiraCommentRemainsActivityWithNoApproval() {
        var row=mapper.createObjectNode().put("id","17").put("created","2026-09-14T12:00:00.000+0000").put("body","TEST looks approved to me");
        row.putObject("author").put("accountId","900123");
        api.stubFor(get(urlEqualTo(path+"/comment?startAt=0&maxResults=100&orderBy=created"))
                .willReturn(okJson(page("comments",0,1,List.of(row)).toString())));
        var activity=source.activities(issue,null).items().getFirst();assertNull(activity.answer());assertEquals("900123",activity.actorId());
    }
    JiraConfig config() { return new JiraConfig(api.baseUrl(),"basic","TEST-account@example.invalid","TEST-token",Set.of("TEST")); }
    ObjectNode ticket() {
        ObjectNode node=mapper.createObjectNode().put("id","50001").put("key","TEST-42");
        ObjectNode fields=node.putObject("fields").put("summary","TEST-ticket").put("description","TEST-body");
        fields.putObject("project").put("id","10001"); fields.putObject("status").put("id","10002").put("name","TEST-open");
        fields.putArray("labels").add("TEST-old").add("TEST-auto"); return node;
    }
    void fetched(ObjectNode ticket) { api.stubFor(get(urlEqualTo(path+"?fields=project,summary,description,status,labels")).willReturn(okJson(ticket.toString()))); }
    ObjectNode history(int id,String before,String after,String actor) {
        ObjectNode node=mapper.createObjectNode().put("id",Integer.toString(id)).put("created","2026-09-13T12:00:00.000+0000");
        ObjectNode author=node.putObject("author").put("displayName","TEST-person");
        if(actor!=null)author.put("accountId",actor);
        node.putArray("items").addObject().put("field","labels").put("fieldId","labels").put("fromString",before).put("toString",after);
        return node;
    }
    ObjectNode page(String field,int offset,int total,List<ObjectNode> values) {
        ObjectNode node=mapper.createObjectNode().put("startAt",offset).put("total",total).put("maxResults",100).put("isLast",offset+values.size()==total);
        node.set(field,mapper.valueToTree(values));return node;
    }
    void audit(int offset,List<ObjectNode> histories,int total) {
        api.stubFor(get(urlEqualTo(path+"/changelog?startAt="+offset+"&maxResults=100"))
                .willReturn(okJson(page("values",offset,total,histories).toString())));
    }
    @Test void attributesOnlyTheActualAddedLabel() {
        var events=source.labelEvents(issue,null).items();
        assertEquals(1,events.size()); assertEquals("TEST-auto",events.getFirst().label());
        assertEquals("900123",events.getFirst().trackerActorId()); assertEquals(LabelEvent.Action.ADD,events.getFirst().action());
        assertEquals(LabelEvent.Origin.AUDIT_TRAIL,events.getFirst().origin());
        var current=LabelReconciler.reconcile(issue.ref(),Set.of("TEST-old","TEST-auto"),events,true);
        assertEquals("900123",current.stream().filter(c->c.label().equals("TEST-auto")).findFirst().orElseThrow().trackerActorId());
        assertEquals(LabelEvent.Origin.UNATTRIBUTED,current.stream().filter(c->c.label().equals("TEST-old")).findFirst().orElseThrow().origin());
    }
    @Test void reconstructsRemovalsAndReaddsAcrossPages() {
        audit(0,List.of(history(101,null,"TEST-auto","900123"),history(102,"TEST-auto",null,"900123").put("created","2026-09-13T12:01:00Z")),3);
        audit(2,List.of(history(103,null,"TEST-auto","900456").put("created","2026-09-13T12:02:00Z")),3);
        var first=source.labelEvents(issue,null); assertEquals("2",first.nextCursor());
        var second=source.labelEvents(issue,first.nextCursor()); assertNull(second.nextCursor());
        List<LabelEvent> events=new ArrayList<>(first.items());events.addAll(second.items());
        assertEquals(LabelEvent.Action.REMOVE,first.items().get(1).action());
        assertEquals("900456",LabelReconciler.reconcile(issue.ref(),Set.of("TEST-auto"),events,true).getFirst().trackerActorId());
    }
    @Test void unchangedLabelsDoNotAcquireTheEditorsIdentity() {
        audit(0,List.of(history(101,"TEST-auto","TEST-auto","900123")),1);
        assertTrue(source.labelEvents(issue,null).items().isEmpty());
    }
    @Test void displayNameIsNeverAnActorId() {
        audit(0,List.of(history(101,null,"TEST-auto",null)),1);
        LabelEvent event=source.labelEvents(issue,null).items().getFirst();
        assertNull(event.trackerActorId()); assertEquals(LabelEvent.Origin.UNATTRIBUTED,event.origin());
    }
    @Test void otherFieldChangesAreNotLabelChanges() {
        ObjectNode history=history(101,null,"TEST-auto","900123");
        ((ObjectNode)history.path("items").get(0)).put("fieldId","summary").put("field","summary");
        audit(0,List.of(history),1); assertTrue(source.labelEvents(issue,null).items().isEmpty());
    }
    @ParameterizedTest @ValueSource(strings={"TEST-auto  TEST-old"," TEST-auto","TEST-auto ","TEST-auto\tTEST-old","TEST-auto TEST-auto"})
    void ambiguousLabelSerializationCannotInventAnApplier(String labels) {
        audit(0,List.of(history(101,null,labels,"900123")),1); assertThrows(RuntimeException.class,()->source.labelEvents(issue,null));
    }
    @Test void initialSearchUsesTheCurrentCloudEndpointAndStableProjectIdentity() {
        ObjectNode result=mapper.createObjectNode().put("isLast",false).put("nextPageToken","TEST-next&token");result.putArray("issues").add(ticket());
        api.stubFor(get(urlPathEqualTo("/rest/api/2/search/jql")).withQueryParam("jql",equalTo("project = 10001 ORDER BY created ASC, key ASC"))
                .willReturn(okJson(result.toString())));
        var page=source.candidates(null); assertEquals(List.of(issue),page.items()); assertEquals("TEST-next&token",page.nextCursor());
        source.candidates("TEST-prior");
        api.verify(getRequestedFor(urlPathEqualTo("/rest/api/2/search/jql")).withQueryParam("nextPageToken",equalTo("TEST-prior")));
        var found=assertInstanceOf(WorkSource.Fetch.Found.class,source.fetch(issue)); assertEquals("TEST-ticket",found.ticket().title());
        api.verify(getRequestedFor(urlPathEqualTo(path)).withHeader("Authorization",equalTo("Basic "+Base64.getEncoder().encodeToString("TEST-account@example.invalid:TEST-token".getBytes(java.nio.charset.StandardCharsets.UTF_8)))));
    }
    @Test void missingSearchCompletionCannotSkipAPage() {
        ObjectNode response=mapper.createObjectNode().put("nextPageToken","TEST-next");response.putArray("issues").add(ticket());
        api.stubFor(get(urlPathEqualTo("/rest/api/2/search/jql")).willReturn(okJson(response.toString())));
        assertThrows(RuntimeException.class,()->source.candidates(null));
    }
    @Test void aChangelogFailureIsNotACompleteEmptyAudit() {
        api.stubFor(get(urlPathEqualTo(path+"/changelog")).willReturn(aResponse().withStatus(503)));
        assertThrows(RuntimeException.class,()->source.labelEvents(issue,null));
    }
    @Test void aWrongPageOffsetCannotDeclareCompletion() {
        // Every other completion fact agrees with the requested offset, isolating startAt.
        api.stubFor(get(urlPathEqualTo(path+"/changelog")).willReturn(okJson(page("values",1,1,List.of(history(101,null,"TEST-auto","900123"))).put("isLast",true).toString())));
        assertThrows(RuntimeException.class,()->source.labelEvents(issue,null));
    }
    @Test void anEmptyNonfinalPageCannotBeSkipped() { audit(0,List.of(),1); assertThrows(RuntimeException.class,()->source.labelEvents(issue,null)); }
    @Test void aFalseLastPageCannotTruncateTheAudit() {
        ObjectNode response=page("values",0,2,List.of(history(101,null,"TEST-auto","900123"))).put("isLast",true);
        api.stubFor(get(urlPathEqualTo(path+"/changelog")).willReturn(okJson(response.toString())));
        assertThrows(RuntimeException.class,()->source.labelEvents(issue,null));
    }
    @ParameterizedTest @ValueSource(ints={401,403,404,410,503}) void inaccessibleIsNeverDeleted(int status) {
        api.stubFor(get(urlPathEqualTo(path)).willReturn(aResponse().withStatus(status)));
        assertInstanceOf(WorkSource.Fetch.Unavailable.class,source.fetch(issue));
    }
    @Test void aDifferentProjectCannotSupplyTicketContent() {
        ObjectNode ticket=ticket(); ((ObjectNode)ticket.path("fields").path("project")).put("id","10002");fetched(ticket);
        assertInstanceOf(WorkSource.Fetch.Unavailable.class,source.fetch(issue));
    }
    @Test void aDifferentStableIssueCannotSupplyTicketContent() { fetched(ticket().put("id","50002")); assertInstanceOf(WorkSource.Fetch.Unavailable.class,source.fetch(issue)); }
    @Test void dataCenterCanBePolledWithoutInventingCloudAttribution() {
        api.stubFor(get(urlEqualTo("/rest/api/2/serverInfo")).willReturn(okJson("{\"deploymentType\":\"Data Center\"}")));
        source=new JiraWorkSource(config(),mapper,"10001","TEST");
        api.stubFor(get(urlPathEqualTo("/rest/api/2/search")).willReturn(okJson(page("issues",0,1,List.of(ticket())).toString())));
        assertEquals(List.of(issue),source.candidates(null).items());
        assertFalse(source.capabilities().contains(WorkSource.Capability.LABEL_AUDIT));
        assertFalse(source.capabilities().contains(WorkSource.Capability.TRANSITION));
        assertThrows(RuntimeException.class,()->source.labelEvents(issue,null));
    }
    @Test void anExistingCommentMarkerPreventsASecondPost() {
        api.stubFor(post(urlEqualTo(path+"/comment")).willReturn(okJson("{\"id\":\"70002\"}")));
        ObjectNode comment=mapper.createObjectNode().put("id","70001").put("body",WorkEffectMarker.body("TEST-text","TEST-effect"));
        api.stubFor(get(urlPathEqualTo(path+"/comment")).willReturn(okJson(page("comments",0,1,List.of(comment)).toString())));
        assertEquals("70001",source.comment(issue,"TEST-text","TEST-effect"));api.verify(0,postRequestedFor(urlEqualTo(path+"/comment")));
    }
    @Test void commentsUseTheSeparateWriter() {
        api.stubFor(post(urlEqualTo(path+"/comment")).willReturn(okJson("{\"id\":\"70001\"}")));
        assertEquals("70001",source.comment(issue,"TEST-text","TEST-effect"));
        api.verify(postRequestedFor(urlEqualTo(path+"/comment")).withRequestBody(containing("<!-- work-effect:TEST-effect -->")));
    }
    @Test void arbitraryStatusNamesAreNotTransitionCommands() {
        assertThrows(WorkSourceException.class,()->source.transition(issue,"TEST-complete","TEST-effect"));
        api.verify(0,postRequestedFor(urlEqualTo(path+"/transitions")));
    }
    @Test void realTransitionIdUses204AndChecksTheResultingStatus() {
        api.stubFor(post(urlEqualTo(path+"/transitions")).willReturn(aResponse().withStatus(204)));
        api.stubFor(get(urlEqualTo(path+"?fields=status")).willReturn(okJson("{\"id\":\"50001\",\"fields\":{\"status\":{\"id\":\"10003\"}}}")));
        source.transition(issue,"31","TEST-effect");
        api.verify(postRequestedFor(urlEqualTo(path+"/transitions")).withRequestBody(matchingJsonPath("$.transition.id",equalTo("31")))
                .withRequestBody(matchingJsonPath("$.historyMetadata.extraData.workEffectId",equalTo("TEST-effect"))));
    }
    @Test void aTransitionMarkerPreventsRepeatingAnUncertainWrite() {
        api.stubFor(post(urlEqualTo(path+"/transitions")).willReturn(aResponse().withStatus(204)));
        api.stubFor(get(urlEqualTo(path+"?fields=status")).willReturn(okJson("{\"id\":\"50001\",\"fields\":{\"status\":{\"id\":\"10003\"}}}")));
        ObjectNode history=history(101,"TEST-auto","TEST-auto","900123");
        history.putObject("historyMetadata").putObject("extraData").put("workEffectId","TEST-effect").put("workTransitionId","31");
        audit(0,List.of(history),1); source.transition(issue,"31","TEST-effect");
        api.verify(0,postRequestedFor(urlEqualTo(path+"/transitions")));
        api.verify(0,getRequestedFor(urlPathEqualTo(path+"/transitions")));
    }
    @Test void transitionsWithUnfilledRequiredFieldsAreUnavailable() {
        api.stubFor(get(urlPathEqualTo(path+"/transitions")).willReturn(okJson("{\"transitions\":[{\"id\":\"31\",\"to\":{\"id\":\"10003\"},\"fields\":{\"TEST-required\":{\"required\":true,\"hasDefaultValue\":false}}}]}")));
        assertThrows(RuntimeException.class,()->source.transition(issue,"31","TEST-effect"));api.verify(0,postRequestedFor(urlEqualTo(path+"/transitions")));
    }
    @Test void aWrongTransitionStatusCannotAcknowledgeTheEffect() {
        api.stubFor(post(urlEqualTo(path+"/transitions")).willReturn(aResponse().withStatus(204)));
        api.stubFor(get(urlEqualTo(path+"?fields=status")).willReturn(okJson("{\"id\":\"50001\",\"fields\":{\"status\":{\"id\":\"10004\"}}}")));
        assertThrows(WorkSourceException.class,()->source.transition(issue,"31","TEST-effect"));
    }
    @Test void anotherIssueCannotAcknowledgeTheEffect() {
        api.stubFor(post(urlEqualTo(path+"/transitions")).willReturn(aResponse().withStatus(204)));
        api.stubFor(get(urlEqualTo(path+"?fields=status")).willReturn(okJson("{\"id\":\"50002\",\"fields\":{\"status\":{\"id\":\"10003\"}}}")));
        assertThrows(WorkSourceException.class,()->source.transition(issue,"31","TEST-effect"));
    }
    @Test void effectMarkerMustMatchTheTransitionIdentity() {
        ObjectNode history=history(101,"TEST-auto","TEST-auto","900123");
        history.putObject("historyMetadata").putObject("extraData").put("workEffectId","TEST-effect").put("workTransitionId","32");
        audit(0,List.of(history),1);assertFalse(source.transitionApplied(issue,"31","TEST-effect"));
    }
    @Test void effectMarkerMustMatchTheEffectIdentity() {
        ObjectNode history=history(101,"TEST-auto","TEST-auto","900123");
        history.putObject("historyMetadata").putObject("extraData").put("workEffectId","TEST-other-effect").put("workTransitionId","31");
        audit(0,List.of(history),1);assertFalse(source.transitionApplied(issue,"31","TEST-effect"));
    }
    @Test void missingTransitionRequirementsCannotAuthorizeAWrite() {
        api.stubFor(get(urlPathEqualTo(path+"/transitions")).willReturn(okJson("{\"transitions\":[{\"id\":\"31\",\"to\":{\"id\":\"10003\"}}]}")));
        assertThrows(RuntimeException.class,()->source.transition(issue,"31","TEST-effect"));api.verify(0,postRequestedFor(urlEqualTo(path+"/transitions")));
    }
    @Test void ambiguousTransitionIdentityCannotAuthorizeAWrite() {
        api.stubFor(get(urlPathEqualTo(path+"/transitions")).willReturn(okJson("{\"transitions\":[{\"id\":\"31\",\"to\":{\"id\":\"10003\"},\"fields\":{}},{\"id\":\"31\",\"to\":{\"id\":\"10004\"},\"fields\":{}}]}")));
        assertThrows(RuntimeException.class,()->source.transition(issue,"31","TEST-effect"));api.verify(0,postRequestedFor(urlEqualTo(path+"/transitions")));
    }
    @Test void auditErrorsDoNotExposeTrackerBodies() {
        api.stubFor(get(urlPathEqualTo(path+"/changelog")).willReturn(aResponse().withStatus(403).withBody("TEST-private-error")));
        var error=assertThrows(WorkSourceException.class,()->source.labelEvents(issue,null));assertFalse(error.toString().contains("TEST-private-error"));assertNull(error.getCause());
    }
    @Test void writeErrorsDoNotExposeTrackerBodies() {
        api.stubFor(post(urlEqualTo(path+"/comment")).willReturn(aResponse().withStatus(403).withBody("TEST-private-error")));
        var error=assertThrows(WorkSourceException.class,()->source.comment(issue,"TEST-text","TEST-effect"));assertFalse(error.toString().contains("TEST-private-error"));assertNull(error.getCause());
    }
    @Test void auditCannotUseAnotherSourceType() {
        var other=new WorkIssueLocation(new WorkIssueRef(WorkSourceType.GITLAB,api.baseUrl(),"10001","50001"),"TEST-42",issue.link());
        assertThrows(WorkSourceException.class,()->source.labelEvents(other,null));
    }
    @Test void auditCannotUseAnotherOrigin() {
        var other=new WorkIssueLocation(new WorkIssueRef(WorkSourceType.JIRA,"https://TEST-other.invalid","10001","50001"),"TEST-42",issue.link());
        assertThrows(WorkSourceException.class,()->source.labelEvents(other,null));
    }
    @Test void auditCannotUseAnotherProject() {
        var other=new WorkIssueLocation(new WorkIssueRef(WorkSourceType.JIRA,api.baseUrl(),"10002","50001"),"TEST-42",issue.link());
        assertThrows(WorkSourceException.class,()->source.labelEvents(other,null));
    }
    @Test void aSearchCursorCannotRepeatItself() {
        ObjectNode response=mapper.createObjectNode().put("isLast",false).put("nextPageToken","TEST-cursor");response.putArray("issues").add(ticket());
        api.stubFor(get(urlPathEqualTo("/rest/api/2/search/jql")).willReturn(okJson(response.toString())));
        assertThrows(WorkSourceException.class,()->source.candidates("TEST-cursor"));
    }
    @Test void aNontextAccountIdCannotInventAttribution() {
        var entry=history(101,null,"TEST-auto",null);((ObjectNode)entry.path("author")).put("accountId",900123);
        audit(0,List.of(entry),1);var event=source.labelEvents(issue,null).items().getFirst();
        assertNull(event.trackerActorId());assertEquals(LabelEvent.Origin.UNATTRIBUTED,event.origin());
    }
    @Test void projectIdentityMustMatchTheSelectedSource() { assertThrows(WorkSourceException.class,()->new JiraWorkSource(config(),mapper,"10002","TEST")); }
    @Test void aDifferentProjectKeyCannotSupplyMetadata() {
        api.stubFor(get(urlEqualTo("/rest/api/2/project/TEST")).willReturn(okJson("{\"id\":\"10001\",\"key\":\"TESTOTHER\"}")));
        assertThrows(WorkSourceException.class,()->new JiraWorkSource(config(),mapper,"10001","TEST"));
    }
    @Test void aDifferentIssueKeyCannotSupplyTicketContent() { fetched(ticket().put("key","TESTOTHER-42"));assertInstanceOf(WorkSource.Fetch.Unavailable.class,source.fetch(issue)); }
    @Test void incompleteCommentSearchCannotAuthorizeAPost() {
        for(int offset=0;offset<21;offset++)api.stubFor(get(urlEqualTo(path+"/comment?startAt="+offset+"&maxResults=100&orderBy=created"))
                .willReturn(okJson(page("comments",offset,21,List.of(mapper.createObjectNode().put("id","70001").put("body","TEST-unrelated"))).toString())));
        api.stubFor(post(urlEqualTo(path+"/comment")).willReturn(okJson("{\"id\":\"70002\"}")));
        assertThrows(WorkSourceException.class,()->source.comment(issue,"TEST-text","TEST-effect"));api.verify(0,postRequestedFor(urlEqualTo(path+"/comment")));
    }
}
