package dev.codespire.runworker;

import dev.codespire.contract.command.*;
import dev.codespire.contract.event.RunResult;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Actual containers and local origin, with a separate real orchestrator and provider HTTP fixture. */
@QuarkusTest
class WorkItemRunJourneyIT extends WorkItemRunFixture {
    @TempDir Path temporary;
    @Test void preparedItemBuildsAndWaitsForVerification() throws Exception {
        Path root=Path.of(System.getProperty("spire.repoRoot"));
        Files.write(temporary.resolve("input.json"),mapper.writeValueAsBytes(java.util.Map.of("baseCommit",origin.baseCommit())));
        Path arguments=temporary.resolve("java.args"),log=temporary.resolve("TEST-control-plane.log");
        String classpath=Files.readString(Path.of(System.getProperty("spire.orchestratorTestClasspathFile"))).replace('\\','/');
        String model=root.resolve("spire-orchestrator/build/quarkus/application-model/quarkus-app-test-model.dat").toString().replace('\\','/');
        Files.writeString(arguments,"-Xmx512m\n-Djava.util.logging.manager=org.jboss.logmanager.LogManager\n-Dquarkus.profile=test\n-Dquarkus.http.test-port=0\n"
                +"\"-Dquarkus-internal-test.serialized-app-model.path="+model+"\"\n-cp\n\""+classpath+"\"\n"
                +"dev.codespire.orchestrator.work.WorkItemRunJourneyProcess\n\""+temporary.toString().replace('\\','/')+"\"\n");
        ProcessBuilder builder=new ProcessBuilder("java","@"+arguments)
                .directory(temporary.toFile()).redirectErrorStream(true).redirectOutput(log.toFile());
        builder.environment().keySet().removeIf(key->key.startsWith("SPIRE_")||key.startsWith("QUARKUS_")||key.startsWith("KAFKA_")||key.startsWith("POSTGRES_"));
        Process child=builder.start();
        try {
            Path commandFile=temporary.resolve("command.json");long deadline=System.nanoTime()+Duration.ofSeconds(120).toNanos();
            while(!Files.isRegularFile(commandFile) && child.isAlive() && System.nanoTime()<deadline)Thread.sleep(50);
            assertTrue(Files.isRegularFile(commandFile),()->"The provider fixture must assemble the prepared command: "+read(log));
            var assembled=mapper.readValue(Files.readAllBytes(commandFile),RunCommand.ExecuteWorkRun.class);
            var input=assembled.execution();ids.add(input.runId());TestImages.clearUnit(input.runId());
            // Explicit TEST transport substitutions: local HTTP origin, test image and its credential.
            // Preparation, pinned base, task, protected paths, run and work binding remain the actual assembly.
            // The test caps wall time at 60 seconds to bound a broken local harness.
            String scm=encryption.encryptString(mapper.writeValueAsString(new MachineAccountCredential(TestOrigin.USER,TestOrigin.SECRET)),RunCommand.scmCredentialAad(input.runId()));
            var execution=new RunCommand.ExecuteRun(input.runId(),input.repo(),origin.remoteUri(),input.baseBranch(),input.baseCommit(),input.branch(),
                    input.prompt(),input.harness(),input.model(),TestImages.AGENT,input.protectedPaths(),60,scm,null);
            execute(new RunCommand.ExecuteWorkRun(execution,assembled.work()));
            var ready=store.find(input.runId()).orElseThrow().ready();assertNotNull(ready);assertNotEquals(input.baseCommit(),ready.head());
            assertEquals(1,builds.get());assertFalse(origin.hasBranch(input.branch()));
            var results=mapper.createArrayNode();reports.forEach(report->results.add(mapper.valueToTree(report)));
            Path staged=temporary.resolve("results.json.tmp");Files.write(staged,mapper.writeValueAsBytes(results));Files.move(staged,temporary.resolve("results.json"),StandardCopyOption.ATOMIC_MOVE);
            assertTrue(child.waitFor(90,TimeUnit.SECONDS),"The control plane must consume readiness and release its services");
            assertEquals(0,child.exitValue(),()->read(log));
            var proof=mapper.readTree(Files.readAllBytes(temporary.resolve("proof.json")));
            assertEquals("verify",proof.path("phase").asText());assertEquals("verify_capability_unavailable",proof.path("reason").asText());
            assertEquals(ready.head(),proof.path("head").asText());assertEquals(input.runId(),proof.path("runId").asText());assertEquals(1,proof.path("runs").asInt());
            assertFalse(origin.hasBranch(input.branch()));assertNull(store.find(input.runId()).orElseThrow().terminal());
            assertFalse(reports.stream().anyMatch(result->result instanceof RunResult.RunFinished));
        } finally {
            if(child.isAlive()){child.destroyForcibly();child.waitFor();}
            Path reports=root.resolve("spire-run-worker/build/reports/work-journey");Files.createDirectories(reports);
            Files.copy(log,reports.resolve("TEST-control-plane.log"),StandardCopyOption.REPLACE_EXISTING);
        }
    }
    static String read(Path path){try{return Files.readString(path);}catch(Exception failure){return failure.getClass().getSimpleName();}}
}
