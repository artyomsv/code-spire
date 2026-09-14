package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.work.WorkPreparation;
import dev.codespire.orchestrator.factory.RunResultSaga;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.time.Duration;
import static org.junit.jupiter.api.Assertions.*;
import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.*;

/** Provider HTTP fixture and real control plane. Actual worker results come from the owning Docker test. */
@QuarkusTest
@TestSecurity(user="TEST-journey-admin",roles="spire-admin")
public class WorkItemRunJourneyDriver extends WorkPreparedFixture {
    @Inject RunResultSaga saga;
    Path directory(){return Path.of(System.getProperty("spire.test.journey.directory"));}
    @Override WorkPreparation preparation(String actor) {
        var value=super.preparation(actor);
        try {
            String head=mapper.readTree(Files.readAllBytes(directory().resolve("input.json"))).path("baseCommit").asText();
            return new WorkPreparation(value.specification(),value.plan(),value.baseBranch(),head,value.harness(),value.model(),value.registeredBy());
        }catch(java.io.IOException failure){throw new IllegalStateException(failure);}
    }
    @Test void drivePreparedBuildAndConsumeItsActualReadyResult() throws Exception {
        String id=admit("autonomous",55);register(id);dispatcher.drain();assertEquals(1,heldCommands.size());
        var command=heldCommands.getFirst();
        write("command.json",mapper.writeValueAsBytes(command));
        long deadline=System.nanoTime()+Duration.ofSeconds(120).toNanos();Path result=directory().resolve("results.json");
        while(!Files.isRegularFile(result) && System.nanoTime()<deadline)Thread.sleep(50);
        assertTrue(Files.isRegularFile(result),"The real worker must report its held build");
        var messages=mapper.readTree(Files.readAllBytes(result));RunResult.RunWorkReady ready=null;
        for(var message:messages) {
            RunResult decoded=mapper.treeToValue(message,RunResult.class);saga.on(decoded);
            if(decoded instanceof RunResult.RunWorkReady checkpoint)ready=checkpoint;
        }
        assertNotNull(ready);assertEquals(command.work(),ready.work());assertNotEquals(command.execution().baseCommit(),ready.head());
        assertEquals("verify",store.load(id).phase());assertEquals("verify_capability_unavailable",store.load(id).reason());
        assertEquals(1,store.load(id).progress().calls());assertEquals(ready.head(),store.load(id).progress().execution().head());
        assertFalse(store.load(id).progress().reserved());
        given().get("/api/work-items/"+id).then().statusCode(200).body("phase",is("verify"),"workflowStatus",is("capability_unavailable"),
                "reason",is("verify_capability_unavailable"),"progress.execution.head",is(ready.head()),"builds.size()",is(1));
        assertEquals(0,count("SELECT count(*) FROM work_delivery_effect WHERE work_item_id=?",id));
        assertEquals(0,count("SELECT count(*) FROM factory_run WHERE work_item_id=? AND pr_number IS NOT NULL",id));
        write("proof.json",mapper.writeValueAsBytes(java.util.Map.of("phase",store.load(id).phase(),"reason",store.load(id).reason(),
                "runId",command.runId(),"head",ready.head(),"runs",runCount(id))));
    }
    void write(String name,byte[] bytes) throws Exception {
        Path staged=directory().resolve(name+".tmp");Files.write(staged,bytes);Files.move(staged,directory().resolve(name),StandardCopyOption.ATOMIC_MOVE);
    }
}
