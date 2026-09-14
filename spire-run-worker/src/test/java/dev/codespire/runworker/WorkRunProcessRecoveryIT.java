package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunResult;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class WorkRunProcessRecoveryIT extends WorkItemRunFixture {
    @TempDir Path directory;
    final List<Process> children=new ArrayList<>();
    @Test void takeoverRevocationSurvivesKilledJvmWithoutAnM1CancelClaim() throws Exception {
        var command=command("takeover-death");String branch=command.execution().branch();
        try {
            Process first=start("build",command);var built=await(first,"ready.json");
            var ready=mapper.treeToValue(built.path("ready"),RunResult.RunWorkReady.class);
            assertEquals(1,built.path("builds").asInt());assertFalse(origin.hasBranch(branch));
            first.destroyForcibly();assertTrue(first.waitFor(10,TimeUnit.SECONDS));
            Process second=start("hold",command);var revoked=await(second,"revoked.json");
            assertTrue(revoked.path("revoked").asBoolean(),"The publication revocation must commit before stopping Docker");
            assertFalse(revoked.path("cancelClaim").asBoolean(),"An M1 cancel must not mask loss of the takeover hold");
            assertEquals("ready",store.find(command.runId()).orElseThrow().state());
            second.destroyForcibly();assertTrue(second.waitFor(10,TimeUnit.SECONDS));
            assertFalse(store.claimPublication(verifiedTestPermit(command,ready)),"A fresh valid permit cannot undo takeover after process death");
            Process third=start("finish",command);var finished=await(third,"published.json");
            assertTrue(third.waitFor(20,TimeUnit.SECONDS));assertEquals(0,third.exitValue());
            assertEquals("CANCELLED",finished.path("terminal").path("cause").asText());
            assertEquals(0,finished.path("builds").asInt());assertFalse(origin.hasBranch(branch));
            assertTrue(((dev.codespire.runtime.PublicationRuntime)runtime).publicationHeld(new dev.codespire.runtime.RunHandle(command.runId(),command.runId())),"Orphan salvage must retain the unpublished workspace");
        } finally {for(Process child:children)if(child.isAlive()){child.destroyForcibly();child.waitFor();}}
    }
    @Test void killedOwnersRetainTheHoldAndResumeOnlyTheClaimedPublisher() throws Exception {
        proof(false);
    }
    @Test void aDeathAfterPushRecoversTheObservedPublicationWithoutRebuilding() throws Exception {
        proof(true);
    }
    void proof(boolean afterPush) throws Exception {
        String mode=afterPush?"observe":"claim";
        var command=command("process-death-"+mode);String branch=command.execution().branch();
        try {
            Process first=start("build",command);var built=await(first,"ready.json");
            var ready=mapper.treeToValue(built.path("ready"),RunResult.RunWorkReady.class);
            assertEquals(1,built.path("builds").asInt());assertFalse(origin.hasBranch(branch));assertNotEquals(origin.baseCommit(),ready.head());
            first.destroyForcibly();assertTrue(first.waitFor(10,TimeUnit.SECONDS));
            Process second=start(mode,command);var recovered=await(second,"recovered-"+mode+".json");
            assertTrue(recovered.path("held").asBoolean());assertEquals(ready.head(),recovered.path("head").asText());
            assertNotEquals(built.path("owner").asText(),recovered.path("owner").asText());
            assertEquals(0,recovered.path("builds").asInt());assertFalse(origin.hasBranch(branch));
            assertNull(store.find(command.runId()).orElseThrow().terminal());
            WorkRunRecoveryProcess.write(mapper,directory,"permit.json",verifiedTestPermit(command,ready));
            var claimed=await(second,"publishing.json");assertEquals("publishing",claimed.path("state").asText());assertEquals(0,claimed.path("builds").asInt());
            assertEquals(afterPush,origin.hasBranch(branch));
            if(afterPush)assertEquals(ready.head(),origin.commitOf(branch));
            assertNull(store.find(command.runId()).orElseThrow().terminal(),"Death must precede the terminal commit in both windows");
            second.destroyForcibly();assertTrue(second.waitFor(10,TimeUnit.SECONDS));
            // Advance only this dead owner's isolated TEST lease, without waiting a production minute.
            try(var c=dataSource.getConnection();var ps=c.prepareStatement("UPDATE runworker.run_lease SET heartbeat_at=now()-interval '2 minutes' WHERE run_id=?")) {
                ps.setString(1,command.runId());assertEquals(1,ps.executeUpdate());
            }
            Process third=start("finish",command);var published=await(third,"published.json");
            assertTrue(third.waitFor(20,TimeUnit.SECONDS));assertEquals(0,third.exitValue(),()->read("finish"));
            var finalResult=mapper.treeToValue(published.path("terminal"),RunResult.RunFinished.class);
            assertEquals("refs/heads/"+branch,finalResult.pushedRef());assertEquals(ready.tokenUsage(),finalResult.tokenUsage());
            assertEquals(ready.head(),origin.commitOf(branch));assertEquals("1",origin.contentOf(branch,"TEST-build-count"));
            assertEquals(0,published.path("builds").asInt());assertEquals(0,published.path("pending").asInt());
            assertTrue(runtime.discoverUnits().stream().noneMatch(unit->unit.runId().equals(command.runId())));
        } finally {
            for(Process child:children)if(child.isAlive()){child.destroyForcibly();child.waitFor();}
            Path reports=Path.of(System.getProperty("spire.repoRoot"),"spire-run-worker/build/reports/work-recovery");Files.createDirectories(reports);
            for(String stage:List.of("build",mode,"finish"))if(Files.exists(directory.resolve(stage+".log")))
                Files.copy(directory.resolve(stage+".log"),reports.resolve("TEST-"+mode+"-"+stage+".log"),StandardCopyOption.REPLACE_EXISTING);
        }
    }
    Process start(String mode,RunCommand.ExecuteWorkRun command) throws Exception {
        var config=org.eclipse.microprofile.config.ConfigProvider.getConfig();var input=mapper.createObjectNode();
        input.put("url",config.getValue("quarkus.datasource.jdbc.url",String.class));
        input.put("username",config.getValue("quarkus.datasource.username",String.class));input.put("password",config.getValue("quarkus.datasource.password",String.class));
        input.put("keyset",config.getValue("spire.encryption.keyset",String.class));input.put("mode",mode);input.set("command",mapper.valueToTree(command));
        String classpath=Files.readString(Path.of(System.getProperty("spire.workerTestClasspathFile"))).replace('\\','/');
        Path arguments=directory.resolve(mode+".args");Files.writeString(arguments,"-Xmx256m\n-Djava.util.logging.manager=org.jboss.logmanager.LogManager\n-cp\n\""+classpath+"\"\ndev.codespire.runworker.WorkRunRecoveryProcess\n\""+directory.toString().replace('\\','/')+"\"\n");
        Process child=new ProcessBuilder("java","@"+arguments)
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(directory.resolve(mode+".log").toFile()).start();
        children.add(child);try(var stdin=child.getOutputStream()){stdin.write(mapper.writeValueAsBytes(input));}return child;
    }
    com.fasterxml.jackson.databind.JsonNode await(Process child,String name) throws Exception {
        Path result=directory.resolve(name);long deadline=System.nanoTime()+Duration.ofSeconds(90).toNanos();
        while(!Files.exists(result) && child.isAlive() && System.nanoTime()<deadline)Thread.sleep(25);
        assertTrue(Files.exists(result),()->"Missing "+name+"\n"+read("build")+read("claim")+read("observe")+read("finish"));
        return mapper.readTree(Files.readAllBytes(result));
    }
    String read(String mode){try{return Files.readString(directory.resolve(mode+".log"));}catch(Exception absent){return "";}}
}
