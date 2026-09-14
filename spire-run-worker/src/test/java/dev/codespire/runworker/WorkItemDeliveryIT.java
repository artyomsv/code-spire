package dev.codespire.runworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.MachineAccountCredential;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.work.WorkPublicationPermit;
import dev.codespire.contract.work.WorkRunBinding;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.harness.*;
import dev.codespire.runtime.*;
import io.quarkus.test.junit.QuarkusMock;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.*;
import javax.sql.DataSource;
import java.sql.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Real publisher proofs. Verification permits are supplied by an explicit TEST-only prior-phase driver. */
@QuarkusTest
class WorkItemDeliveryIT extends WorkItemRunFixture {
    @Test void deliverOffNeverPushesTheBuiltBranch() throws Exception {
        var command=command("off");String branch=command.execution().branch();
        origin.branchFrom(branch,"main");String before=origin.commitOf(branch);
        execute(command);
        // FIRST assertion: removing the initial publisher hold must fail on the real remote head,
        // even if the worker notices the escaped push and reports a failure instead of readiness.
        assertEquals(before,origin.commitOf(branch),"deliver off must leave the real remote head unchanged");
        var held=store.find(command.runId()).orElseThrow();
        assertEquals("ready",held.state());assertNotNull(held.ready());assertNull(held.terminal());assertNull(held.permit());
        assertNotEquals(before,held.ready().head(),"a real new commit was built; unchanged remote is not an empty-work proof");
        assertEquals(Map.of("INPUT",7L),held.ready().tokenUsage());
        assertEquals(1,builds.get());
        assertTrue(((PublicationRuntime)runtime).publicationHeld(new RunHandle(command.runId(),held.unitId())));
        assertFalse(reports.stream().anyMatch(result->result instanceof RunResult.RunFinished));
    }

    @Test void deliveryPermitPublishesWithoutRebuilding() throws Exception {
        var command=command("permit");execute(command);
        var ready=store.find(command.runId()).orElseThrow().ready();assertNotNull(ready);
        assertFalse(origin.hasBranch(command.execution().branch()));
        // TEST-only prior-phase driver: verify is supplied here, not implemented or claimed in production.
        var permit=verifiedTestPermit(command,ready);
        control.onControl(permit);
        var terminal=assertInstanceOf(RunResult.RunFinished.class,store.find(command.runId()).orElseThrow().terminal());
        assertEquals(ready.head(),origin.commitOf(command.execution().branch()));
        assertEquals("1",origin.contentOf(command.execution().branch(),"TEST-build-count"));
        assertEquals(1,builds.get(),"publication must not construct another harness invocation");
        assertEquals(ready.tokenUsage(),terminal.tokenUsage());
        assertEquals("refs/heads/"+command.execution().branch(),terminal.pushedRef());
        control.onControl(permit);worker.recover();
        assertEquals(1,builds.get());assertEquals(1,reports.stream().filter(r->r instanceof RunResult.RunFinished).count());
        assertFalse(runtime.discoverUnits().stream().anyMatch(unit->unit.runId().equals(command.runId())),"cleanup follows the durable terminal result");
    }

    @Test void executeRedeliveryCannotRebuildRetainedWork() throws Exception {
        var command=command("redelivery");execute(command);
        var ready=store.find(command.runId()).orElseThrow().ready();assertNotNull(ready);
        execute(command);worker.recover();
        assertEquals(1,builds.get());assertEquals(ready,store.find(command.runId()).orElseThrow().ready());
        assertFalse(origin.hasBranch(command.execution().branch()));
    }

    @Test void workReadyAndFinishedDoNotDoubleCharge() throws Exception {
        var command=command("charge");execute(command);
        var ready=store.find(command.runId()).orElseThrow().ready();assertNotNull(ready);
        control.onControl(verifiedTestPermit(command,ready));
        var finished=assertInstanceOf(RunResult.RunFinished.class,store.find(command.runId()).orElseThrow().terminal());
        assertEquals(ready.head(),origin.commitOf(command.execution().branch()));
        var config=org.eclipse.microprofile.config.ConfigProvider.getConfig();
        var input=mapper.createObjectNode();
        input.put("url",config.getValue("quarkus.datasource.jdbc.url",String.class));
        input.put("username",config.getValue("quarkus.datasource.username",String.class));
        input.put("password",config.getValue("quarkus.datasource.password",String.class));
        input.put("root",System.getProperty("spire.repoRoot"));
        input.set("ready",mapper.valueToTree(ready));input.set("finished",mapper.valueToTree(finished));
        Path directory=Files.createTempDirectory("TEST-work-charge-");
        Path arguments=directory.resolve("java.args"),log=directory.resolve("proof.log");
        String classpath=Files.readString(Path.of(System.getProperty("spire.orchestratorTestClasspathFile"))).replace('\\','/');
        Files.writeString(arguments,"-cp\n\""+classpath+"\"\ndev.codespire.orchestrator.factory.WorkRunChargeProof\n");
        Process child=new ProcessBuilder("java","@"+arguments)
                .directory(directory.toFile()).redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            try(var stdin=child.getOutputStream()){stdin.write(mapper.writeValueAsBytes(input));}
            assertTrue(child.waitFor(60,TimeUnit.SECONDS),"production charge consumer must finish");
            String output=Files.readString(log,StandardCharsets.UTF_8);
            assertEquals(0,child.exitValue(),output);
            String evidence=output.lines().filter(line->line.startsWith("TEST_CHARGE_PROOF ")).findFirst().orElseThrow();
            var proof=mapper.readTree(evidence.substring("TEST_CHARGE_PROOF ".length()));
            // Assert both observations: skipping readiness cannot pass through final-result charging.
            for(String observation:List.of("afterReady","afterRedelivery")) {
                var counts=proof.path(observation);
                assertEquals(1,counts.path("rows").asInt(),observation+": one charge line");
                assertEquals(1,counts.path("calls").asInt(),observation+": one charge identity");
                assertEquals(7,counts.path("tokens").asInt(),observation+": measured worker usage");
                assertEquals(7,counts.path("cost").asInt(),observation+": fixed TEST rate");
                assertEquals(0,counts.path("unknown").asInt(),observation+": measured usage remains priced");
            }
        } finally {
            if(child.isAlive()){child.destroyForcibly();child.waitFor();}
            Files.deleteIfExists(arguments);Files.deleteIfExists(log);Files.deleteIfExists(directory);
        }
    }

    @Test void aCancellationBeforeThePermitRetainsUnpublishedWork() throws Exception {
        var command=command("cancelled");execute(command);
        var ready=store.find(command.runId()).orElseThrow().ready();assertNotNull(ready);
        control.onControl(new RunCommand.CancelRun(command.runId(),"TEST cancellation"));
        control.onControl(verifiedTestPermit(command,ready));worker.recover();
        assertFalse(origin.hasBranch(command.execution().branch()));
        var terminal=assertInstanceOf(RunResult.RunFailed.class,store.find(command.runId()).orElseThrow().terminal());
        assertEquals("CANCELLED",terminal.cause());assertEquals(ready.tokenUsage(),terminal.tokenUsage());
        assertEquals(1,builds.get());
        assertTrue(((PublicationRuntime)runtime).publicationHeld(new RunHandle(command.runId(),command.runId())));
    }

}
