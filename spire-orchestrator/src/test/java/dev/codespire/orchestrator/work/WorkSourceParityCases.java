package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.codespire.contract.work.*;
import dev.codespire.contract.event.WorkItemIds;
import dev.codespire.orchestrator.provider.ProviderInput;
import dev.codespire.orchestrator.repository.RepositoryInput;
import dev.codespire.worksource.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/** Every arm exercises the actual source/account registry, fetched tracker evidence and JDBC store. */
abstract class WorkSourceParityCases extends WorkFixture {
    abstract WorkSourceType type();
    boolean jira() { return type() == WorkSourceType.JIRA; }
    @Override @BeforeEach void seedWork() {
        if (type() == WorkSourceType.GITHUB) {
            super.seedWork();
            forge.stubFor(get(urlEqualTo("/repos/"+scope+"/issues?state=open&sort=created&direction=asc&per_page=100&page=1"))
                    .willReturn(okJson(mapper.createArrayNode().add(ticket()).toString())));
            return;
        }
        forge=new WireMockServer(WireMockConfiguration.options().dynamicPort());forge.start();
        String slug="TEST-"+UUID.randomUUID(); scope=jira()?"TEST":"TEST-work/"+slug;
        account=UUID.fromString(providers.create(new ProviderInput("TEST-parity-account-"+UUID.randomUUID(),jira()?"atlassian":"gitlab",forge.baseUrl()+(jira()?"":"/api/v4"),
                jira()?"basic":"bearer",jira()?"TEST-account@example.invalid":null,"TEST-token","TEST-bot",true,List.of(),"TEST-bot",null,jira()?"CONTEXT":"FACTORY")).id());
        repository=repositories.create(new RepositoryInput(jira()?"github":"gitlab",jira()?"https://TEST-scm.example.invalid":forge.baseUrl(),"TEST-work",slug,true,null,jira()?null:account)).id();
        if(jira()) {
            forge.stubFor(get(urlEqualTo("/rest/api/2/project/TEST")).willReturn(okJson("{\"id\":\"10001\",\"key\":\"TEST\"}")));
            forge.stubFor(get(urlEqualTo("/rest/api/2/serverInfo")).willReturn(okJson("{\"deploymentType\":\"Cloud\"}")));
            forge.stubFor(get(urlPathEqualTo("/rest/api/3/user/search")).withQueryParam("query",equalTo("TEST-person"))
                    .willReturn(okJson("[{\"accountId\":\"900123\",\"displayName\":\"TEST-person\",\"active\":true}]")));
            forge.stubFor(get(urlEqualTo("/rest/api/3/user?accountId=900123")).willReturn(okJson("{\"accountId\":\"900123\",\"displayName\":\"TEST-person\",\"active\":true}")));
        } else {
            forge.stubFor(get(urlEqualTo("/api/v4/projects/TEST-work%2F"+slug)).willReturn(okJson(mapper.createObjectNode().put("id",10001)
                    .put("path_with_namespace",scope).put("web_url",forge.baseUrl()+"/"+scope).toString())));
            forge.stubFor(get(urlEqualTo("/api/v4/users?username=TEST-person&per_page=100")).willReturn(okJson("[{\"id\":900123,\"username\":\"TEST-person\",\"name\":\"TEST-person\",\"state\":\"active\"}]")));
            forge.stubFor(get(urlEqualTo("/api/v4/users/900123")).willReturn(okJson("{\"id\":900123,\"username\":\"TEST-person\",\"name\":\"TEST-person\",\"state\":\"active\"}")));
        }
        source=administration.create(new WorkSourceAdministration.Input("TEST-parity-source-"+UUID.randomUUID(),type(),forge.baseUrl(),scope,repository,account,true)).id();
        administration.saveActor(source,new WorkSourceAdministration.ActorInput("TEST-person","900123",1));
        profile=UUID.randomUUID();
        EnumMap<WorkPolicy.Phase,String> modes=new EnumMap<>(WorkPolicy.Phase.class);
        for(var phase:WorkPolicy.Phase.values())modes.put(phase,switch(phase){case DELIVER->"pr";case LAND->"auto_if_green";default->"auto";});
        policies.createVersion(new WorkPolicy.Profile(profile,"TEST-parity-profile-"+profile,1,Math.abs(profile.hashCode()%1000000000),modes));
        var pin=new WorkPolicyRegistry.Pin(profile,1);policies.save(repository,new WorkPolicyRegistry.Input(0,pin,Map.of(LABEL,pin)));
        issue=new WorkIssueLocation(new WorkIssueRef(type(),forge.baseUrl(),"10001","50001"),jira()?"TEST-42":"42",
                URI.create(forge.baseUrl()+(jira()?"/browse/TEST-42":"/"+scope+"/-/issues/42")));
        var registered=sources.get(source).orElseThrow();
        itemId=WorkItemIds.of(registered.scm(),registered.forgeOrigin(),registered.repository(),issue.ref());
        path=jira()?"/rest/api/2/issue/50001":"/api/v4/projects/10001/issues/42";
        forge.stubFor(get(urlPathEqualTo(path)).willReturn(okJson(ticket().toString())));
        if(jira()) {
            ObjectNode page=mapper.createObjectNode().put("isLast",true);page.putArray("issues").add(ticket());
            forge.stubFor(get(urlPathEqualTo("/rest/api/2/search/jql")).willReturn(okJson(page.toString())));
        } else forge.stubFor(get(urlPathEqualTo("/api/v4/projects/10001/issues")).willReturn(okJson(mapper.createArrayNode().add(ticket()).toString()).withHeader("X-Next-Page","")));
        audit("900123");
    }
    @Override ObjectNode ticket() {
        if(type() == WorkSourceType.GITHUB) return super.ticket();
        if(!jira()) {
            ObjectNode node=mapper.createObjectNode().put("id",50001).put("iid",42).put("project_id",10001)
                    .put("title","TEST-parity-title").put("description","TEST-parity-body").put("state","opened");node.putArray("labels").add(LABEL);return node;
        }
        ObjectNode node=mapper.createObjectNode().put("id","50001").put("key","TEST-42");
        ObjectNode fields=node.putObject("fields").put("summary","TEST-parity-title").put("description","TEST-parity-body");
        fields.putObject("project").put("id","10001");fields.putObject("status").put("name","TEST-open");fields.putArray("labels").add(LABEL);return node;
    }
    ObjectNode labelEvent(String actor) {
        ObjectNode event=mapper.createObjectNode().put("created_at","2026-09-13T12:00:00Z");
        if(type() == WorkSourceType.GITHUB) {
            event.put("id",101).put("event","labeled");event.putObject("label").put("name",LABEL);
            if(actor==null)event.putNull("actor");else event.putObject("actor").put("id",Long.parseLong(actor));
            return event;
        }
        if(!jira()) {
            event.put("id",101).put("resource_id",50001).put("resource_type","Issue").put("action","add");
            event.putObject("label").put("name",LABEL); if(actor==null)event.putNull("user");else event.putObject("user").put("id",Long.parseLong(actor));
        } else {
            event.put("id","101").put("created","2026-09-13T12:00:00.000+0000");
            event.putArray("items").addObject().put("fieldId","labels").putNull("fromString").put("toString",LABEL);
            ObjectNode author=event.putObject("author").put("displayName","TEST-person");if(actor!=null)author.put("accountId",actor);
        }
        return event;
    }
    @Override void audit(String actor) { auditPage(actor,false); }
    void auditPage(String actor,boolean incomplete) {
        if(type() == WorkSourceType.GITHUB) {
            var response=okJson(mapper.createArrayNode().add(labelEvent(actor)).toString());
            if(incomplete)response.withHeader("Link","<"+forge.baseUrl()+path+"/timeline?per_page=100&page=2>; rel=\"next\"");
            forge.stubFor(get(urlEqualTo(path+"/timeline?per_page=100&page=1")).willReturn(response));
            forge.stubFor(get(urlEqualTo(path+"/timeline?per_page=100&page=2")).willReturn(aResponse().withStatus(503)));
        } else if(jira()) {
            ObjectNode page=mapper.createObjectNode().put("startAt",0).put("total",incomplete?2:1).put("isLast",!incomplete);page.putArray("values").add(labelEvent(actor));
            forge.stubFor(get(urlEqualTo(path+"/changelog?startAt=0&maxResults=100")).willReturn(okJson(page.toString())));
            forge.stubFor(get(urlEqualTo(path+"/changelog?startAt=1&maxResults=100")).willReturn(aResponse().withStatus(503)));
        } else {
            forge.stubFor(get(urlEqualTo(path+"/resource_label_events?per_page=100&page=1"))
                    .willReturn(okJson(mapper.createArrayNode().add(labelEvent(actor)).toString()).withHeader("X-Next-Page",incomplete?"2":"")));
            forge.stubFor(get(urlEqualTo(path+"/resource_label_events?per_page=100&page=2")).willReturn(aResponse().withStatus(503)));
        }
    }
    @Test void unlistedLabellerSelectsNoProfile() throws Exception {
        audit("900456");assertTrue(scanner.scan(source));
        WorkItemEvent item=store.load(itemId);assertNotNull(item);assertNull(item.policy().selected());
        assertEquals("actor_not_allowed",item.policy().ignored().getFirst().reason());noEffects();
    }
    @Test void unattributedCurrentLabelSelectsNoProfile() throws Exception {
        auditPage("900123",true);assertTrue(scanner.scan(source));
        WorkItemEvent item=store.load(itemId);assertNotNull(item);assertNull(item.policy().selected());
        assertEquals("900123",item.policy().ignored().getFirst().actorId(),"the hint must pass membership");
        assertEquals(LabelEvent.Origin.UNATTRIBUTED,item.policy().ignored().getFirst().origin());
        assertEquals("label_unattributed",item.policy().ignored().getFirst().reason());noEffects();
    }
    @Test void allowedAttributedLabellerCanSelect() throws Exception {
        assertTrue(scanner.scan(source));WorkItemEvent item=store.load(itemId);assertEquals(profile,item.policy().selected().id());
        assertTrue(item.policy().ignored().isEmpty());assertEquals("awaiting_input",item.workflowStatus());
        assertEquals("specification_required",item.reason());
        assertEquals(1,count("SELECT count(*) FROM work_item_outbox WHERE work_item_id=?",itemId));
    }
    @Test void aGenuinelyMissingActorSelectsNoProfile() throws Exception {
        audit(null);assertTrue(scanner.scan(source));WorkItemEvent item=store.load(itemId);assertNull(item.policy().selected());
        assertEquals("actor_id_missing",item.policy().ignored().getFirst().reason());noEffects();
    }
    @Test void rescanningDoesNotDuplicateTheItemOrHistory() throws Exception {
        assertTrue(scanner.scan(source));assertTrue(scanner.scan(source));
        assertEquals(1,count("SELECT count(*) FROM work_item WHERE id=?",itemId));assertEquals(1,store.history(itemId).size());
    }
    void noEffects() throws Exception {
        assertEquals(0,count("SELECT count(*) FROM work_item_outbox WHERE work_item_id=? AND effect_type <> 'WORK_EVENT'",itemId));
        assertEquals(0,count("SELECT count(*) FROM work_tracker_outbox WHERE work_item_id=?",itemId));
        assertEquals(0,count("SELECT count(*) FROM factory_run WHERE work_item_id=?",itemId));
        assertEquals(0,count("SELECT count(*) FROM work_item_gate WHERE work_item_id=?",itemId));
    }
}
