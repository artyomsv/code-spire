package dev.codespire.orchestrator.work;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.WorkItemIds;
import dev.codespire.contract.work.*;
import dev.codespire.orchestrator.factory.*;
import dev.codespire.worksource.*;
import io.quarkus.test.junit.QuarkusMock;
import jakarta.inject.Inject;
import org.junit.jupiter.api.*;
import java.util.*;
import java.sql.*;
import java.net.URI;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/** Real assembly and database claims; only the final worker transport is explicitly TEST-only. */
abstract class WorkPreparedFixture extends WorkFixture {
    @Inject WorkItemTransitions transitions;
    @Inject WorkArtifacts artifacts;
    @Inject WorkRunDispatcher dispatcher;
    @Inject HarnessCredentialPool pool;
    @Inject FactoryConfig factoryConfig;
    @Inject RunLaunch actualLaunch;
    final List<RunCommand.ExecuteRun> dispatched=new ArrayList<>();
    final List<RunCommand.ExecuteWorkRun> heldCommands=new ArrayList<>();
    final Map<String,WorkPolicy.Profile> profiles=new LinkedHashMap<>();
    final String model="TEST-prepared-"+UUID.randomUUID();
    final UUID modelId=UUID.randomUUID();
    UUID credential;
    boolean publicationSupported=true;
    IllegalStateException brokerFailure;
    String specification="TEST-existing tracker specification: implement the named prepared task.";
    String plan;
    static final String BASE="a".repeat(40);

    @BeforeEach void prepareFixture() throws Exception {
        QuarkusMock.installMockForType(new RunCommandEmitter() {
            @Override public void dispatch(RunCommand command) {
                try { assertEquals(1,count("SELECT count(*) FROM work_run_effect e JOIN factory_run r ON r.run_id=e.run_id WHERE e.run_id=? AND e.state='uncertain' AND r.work_item_id=e.work_item_id",command.runId()),"The associated claim must be committed before dispatch"); }
                catch(SQLException failure){throw new AssertionError(failure);}
                var held=assertInstanceOf(RunCommand.ExecuteWorkRun.class,command);
                heldCommands.add(held);dispatched.add(held.execution());
                if(brokerFailure!=null)throw brokerFailure;
            }
        },RunCommandEmitter.class);
        QuarkusMock.installMockForType(new WorkRunTransport() {
            @Override public boolean available(){return publicationSupported && super.available();}
            @Override public RunLaunch.Outcome dispatch(RunCommand.ExecuteWorkRun command) {
                // The real held-command launcher runs; only the final broker emitter is a TEST boundary.
                assertTrue(publicationSupported);return actualLaunch.launch(command);
            }
        },WorkRunTransport.class);
        credential=pool.add("TEST-prepared-pool-"+UUID.randomUUID(),"openai","https://api.openai.com","TEST-prepared-key").id();
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("INSERT INTO llm_model(id,type,name,label,pricing_mode) VALUES (?,'openai',?,?,'UNMETERED')")) {
            ps.setObject(1,modelId);ps.setString(2,model);ps.setString(3,model);ps.executeUpdate();
        }
        for(String name:List.of("suggest","assisted","autonomous")) {
            var modes=new EnumMap<WorkPolicy.Phase,String>(WorkPolicy.Phase.class);
            for(var phase:WorkPolicy.Phase.values())modes.put(phase,switch(phase){case DELIVER->"pr";case LAND->"auto_if_green";default->"auto";});
            if(name.equals("suggest"))for(var phase:List.of(WorkPolicy.Phase.BUILD,WorkPolicy.Phase.VERIFY,WorkPolicy.Phase.REVIEW,WorkPolicy.Phase.DELIVER,WorkPolicy.Phase.LAND))modes.put(phase,"off");
            if(name.equals("assisted")){modes.put(WorkPolicy.Phase.PLAN,"approve");modes.put(WorkPolicy.Phase.DELIVER,"draft_pr");modes.put(WorkPolicy.Phase.LAND,"approve");}
            UUID id=UUID.randomUUID();extraProfiles.add(id);
            profiles.put(name,policies.createVersion(new WorkPolicy.Profile(id,"TEST-"+name+"-"+id,1,1_000_000_401+profiles.size(),modes,
                    new WorkPolicyLimits(3600,5,20,7200,2_000_000,40,Set.of("TEST-sensitive/**")))));
        }
        Map<String,WorkPolicyRegistry.Pin> mapping=new HashMap<>();profiles.forEach((name,p)->mapping.put("TEST-"+name,new WorkPolicyRegistry.Pin(p.id(),1)));
        policies.save(repository,new WorkPolicyRegistry.Input(1,new WorkPolicyRegistry.Pin(profiles.get("autonomous").id(),1),mapping));
        plan=mapper.writeValueAsString(Map.of("schemaVersion",1,"specificationSha256",WorkPreparation.digest(specification),"steps",List.of(Map.of("id","TEST-step","instruction","TEST-implement this one existing task"))));
        stubArtifact(71,57001,specification);stubArtifact(72,57002,plan);
    }
    void stubArtifact(int number,long stable,String body) {
        var value=mapper.createObjectNode().put("id",stable).put("number",number).put("repository_url",forge.baseUrl()+"/repos/"+scope)
                .put("html_url",forge.baseUrl()+"/"+scope+"/issues/"+number).put("title","TEST-artifact-"+number).put("body",body).put("state","open");value.putArray("labels");
        forge.stubFor(get(urlEqualTo("/repos/"+scope+"/issues/"+number)).willReturn(okJson(value.toString())));
    }
    String admit(String name,int number) throws Exception {
        var ref=new WorkIssueRef(WorkSourceType.GITHUB,forge.baseUrl(),"10001",Long.toString(50000+number));
        var location=new WorkIssueLocation(ref,Integer.toString(number),URI.create(forge.baseUrl()+"/"+scope+"/issues/"+number));
        var sourceRow=sources.get(source).orElseThrow();String id=WorkItemIds.of(sourceRow.scm(),sourceRow.forgeOrigin(),sourceRow.repository(),ref);extraItems.add(id);
        var ticket=mapper.createObjectNode().put("id",50000+number).put("number",number).put("repository_url",forge.baseUrl()+"/repos/"+scope)
                .put("html_url",location.link().toString()).put("title","TEST-prepared-task").put("body","TEST-identical task").put("state","open");ticket.putArray("labels").add("TEST-"+name);
        String endpoint="/repos/"+scope+"/issues/"+number;
        forge.stubFor(get(urlEqualTo(endpoint)).willReturn(okJson(ticket.toString())));
        var event=mapper.createObjectNode().put("id",100+number).put("event","labeled").put("created_at","2026-09-14T00:00:00Z");event.putObject("label").put("name","TEST-"+name);event.putObject("actor").put("id",900123);
        forge.stubFor(get(urlEqualTo(endpoint+"/timeline?per_page=100&page=1")).willReturn(okJson(mapper.createArrayNode().add(event).toString())));
        var evidence=WorkEvidence.collect(()->sources.client(sourceRow),location,null);
        assertNull(evidence.failure());store.reconcile(sourceRow,policies.get(repository).revision(),evidence,"TEST-prepared-intake-"+number);
        return id;
    }
    WorkPreparation preparation(String actor) {
        var row=sources.get(source).orElseThrow();
        return new WorkPreparation(artifacts.resolve(row,"71").artifact(),artifacts.resolve(row,"72").artifact(),"main",BASE,"codex",model,actor);
    }
    void register(String id) {
        var outcome=transitions.prepare(id,store.history(id).size(),preparation("TEST-prepared-admin"));assertEquals(200,outcome.status(),outcome.reason());
    }
    long runCount(String id) throws Exception {return count("SELECT count(*) FROM factory_run WHERE work_item_id=?",id);}
    @Override @AfterEach void cleanWork() throws Exception {
        try {super.cleanWork();}
        finally {if(credential!=null)pool.remove(credential);execute("DELETE FROM llm_model WHERE id=?",modelId);}
    }
}
