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

/** Real worker/store/containers and remote. Permit construction is an explicit TEST-only prior-phase driver. */
abstract class WorkItemRunFixture {
    static TestOrigin origin;
    @Inject WorkRunWorker worker;
    @Inject WorkRunStore store;
    @Inject RunDispatcher dispatcher;
    @Inject RunControlListener control;
    @Inject RunRuntime runtime;
    @Inject DataSource dataSource;
    @Inject EncryptionService encryption;
    @Inject ObjectMapper mapper;
    final List<String> ids=new ArrayList<>();
    final List<RunResult> reports=new CopyOnWriteArrayList<>();
    final AtomicInteger builds=new AtomicInteger();

    @BeforeAll static void start() throws Exception {TestImages.buildAll();origin=TestOrigin.start();}
    @AfterAll static void stop(){if(origin!=null)origin.close();}
    @BeforeEach void observations() {
        QuarkusMock.installMockForType(new RunResultReporter(){@Override public boolean report(RunResult result){reports.add(result);return true;}},RunResultReporter.class);
        QuarkusMock.installMockForType(new HarnessRegistry(){@Override public HarnessAdapter forName(String name){return testHarness(builds);}},HarnessRegistry.class);
    }
    @AfterEach void deleteExactFixtures() throws Exception {
        for(String id:ids)TestImages.clearUnit(id);
        try(Connection c=dataSource.getConnection()) {
            for(String id:ids) {
                try(PreparedStatement ps=c.prepareStatement("DELETE FROM runworker.work_publication_revocation WHERE run_id=?")){ps.setString(1,id);ps.executeUpdate();}
                try(PreparedStatement ps=c.prepareStatement("DELETE FROM runworker.work_run WHERE run_id=?")){ps.setString(1,id);ps.executeUpdate();}
                try(PreparedStatement ps=c.prepareStatement("DELETE FROM runworker.run_claim WHERE run_id=?")){ps.setString(1,id);ps.executeUpdate();}
                try(PreparedStatement ps=c.prepareStatement("DELETE FROM runworker.run_lease WHERE run_id=?")){ps.setString(1,id);ps.executeUpdate();}
            }
        }
    }

    void execute(RunCommand.ExecuteWorkRun command) {
        var acked=new java.util.concurrent.atomic.AtomicBoolean();
        dispatcher.onCommand(Message.of((RunCommand)command,()->{acked.set(true);return java.util.concurrent.CompletableFuture.completedFuture(null);})).toCompletableFuture().join();
        assertTrue(acked.get());
    }

    RunCommand.PublishWorkRun verifiedTestPermit(RunCommand.ExecuteWorkRun command,RunResult.RunWorkReady ready) {
        return new RunCommand.PublishWorkRun(command.runId(),new WorkPublicationPermit(command.work(),UUID.randomUUID(),ready.head(),
                Instant.now().minusSeconds(1),Instant.now().plusSeconds(90),List.of()),command.scmCredential());
    }

    RunCommand.ExecuteWorkRun command(String suffix) throws Exception {
        String subject="TEST-held-"+suffix+"-"+UUID.randomUUID().toString().substring(0,8);
        String id="run::github:TEST-work/app:"+subject+":1";ids.add(id);TestImages.clearUnit(id);
        String scm=encryption.encryptString(mapper.writeValueAsString(new MachineAccountCredential(TestOrigin.USER,TestOrigin.SECRET)),RunCommand.scmCredentialAad(id));
        var command=new RunCommand.ExecuteRun(id,new RepoRef("TEST-work","app"),origin.remoteUri(),"main",origin.baseCommit(),
                "spire/"+subject,"TEST prepared build instruction","script","TEST-model",TestImages.AGENT,List.of(),60,scm,null);
        return new RunCommand.ExecuteWorkRun(command,new WorkRunBinding("TEST-item-"+subject,1,UUID.randomUUID(),"a".repeat(64)));
    }

    static HarnessAdapter testHarness(AtomicInteger builds) {
        var delegate=new ScriptHarness("n=$(cat TEST-build-count 2>/dev/null || echo 0); echo $((n+1)) > TEST-build-count; "
                +"echo TEST-prepared-work > TEST-result.txt; echo TEST_USAGE_INPUT_7");
        return new HarnessAdapter() {
            public HarnessType type(){return delegate.type();}
            public HarnessCapabilities capabilities(){return delegate.capabilities();}
            public PromptDelivery promptDelivery(){return delegate.promptDelivery();}
            public List<String> command(HarnessInvocation invocation){builds.incrementAndGet();return delegate.command(invocation);}
            public Map<String,String> environment(HarnessInvocation invocation){return delegate.environment(invocation);}
            public Optional<RunEvent> parse(String line){return line.equals("TEST_USAGE_INPUT_7")
                    ?Optional.of(new RunEvent.Usage(Instant.now(),UsageReport.of(Map.of(TokenBucket.INPUT,7L)))):Optional.empty();}
            public TerminalOutcome classify(int exit,RunEventSummary summary){return delegate.classify(exit,summary);}
            public UsageReport usage(RunEventSummary summary){return summary.events().stream().filter(e->e instanceof RunEvent.Usage)
                    .map(e->((RunEvent.Usage)e).report()).reduce((first,last)->last).orElse(UsageReport.unknown());}
        };
    }
}
