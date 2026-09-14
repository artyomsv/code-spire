package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.work.WorkRunBinding;
import dev.codespire.harness.*;
import dev.codespire.runtime.*;
import org.junit.jupiter.api.*;
import java.util.*;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

class HeldRunLauncherTest {
    static final String HEAD="a".repeat(40);
    static final RunCommand.ExecuteWorkRun COMMAND=new RunCommand.ExecuteWorkRun(new RunCommand.ExecuteRun(
            "run::github:TEST-work/app:TEST-held:1",new RepoRef("TEST-work","app"),"https://forge.example.test/TEST-work/app.git",
            "main","b".repeat(40),"spire/TEST-held","TEST instruction","TEST-harness","TEST-model","TEST-image",List.of(),60,"TEST-scm","TEST-harness"),
            new WorkRunBinding("TEST-work",1,UUID.randomUUID(),"c".repeat(64)));
    static final String CHECKPOINT="{\"event\":\"checkpoint\",\"head\":\""+HEAD+"\",\"changed\":[{\"path\":\"TEST-file\",\"kind\":\"MODIFIED\"}]}";
    static class HeldRuntime extends RunLauncherTest.FakeRuntime implements PublicationRuntime {
        PublicationKey binding;
        @Override public RunHandle createHeld(RunUnitSpec unit,PublicationKey key){binding=key;lifecycle.add("create-held");return new RunHandle(COMMAND.runId(),"TEST-unit");}
        @Override public boolean publicationHeld(RunHandle run){return binding!=null;}
        @Override public Finalization publishHeld(RunHandle run,PublicationKey key,UUID permit,RunUnitSpec unit,Consumer<String> lines,BooleanSupplier mayStart){throw new UnsupportedOperationException("TEST does not publish");}
        @Override public void destroyHeld(RunHandle run,PublicationKey key){throw new UnsupportedOperationException("TEST must retain");}
    }
    final HeldRuntime runtime=new HeldRuntime();
    final RunLauncherTest.FakeAdapter adapter=new RunLauncherTest.FakeAdapter();
    final RunLauncher launcher=RunLauncherTest.launcher(runtime,adapter);
    @BeforeEach void fixture(){
        adapter.usage=UsageReport.of(Map.of(TokenBucket.INPUT,7L));runtime.publisherLines=List.of(CHECKPOINT);
        launcher.builder=new RunUnitBuilder(){
            @Override public RunUnitSpec build(RunCommand.ExecuteRun command,HarnessAdapter harness){throw new AssertionError("Held work cannot use automatic topology");}
            @Override public RunUnitSpec buildHeld(RunCommand.ExecuteWorkRun command,HarnessAdapter harness){return null; /* TEST runtime reads no topology; real builder is covered by delivery IT. */}
        };
    }
    @AfterEach void threads(){launcher.stopStreams();}
    RunResult launch(){return launcher.launchHeld(COMMAND,RunObserver.IGNORING,unit->{});}
    @Test void anObservedCheckpointBecomesReadinessWithMeasuredUsage(){
        var ready=assertInstanceOf(RunResult.RunWorkReady.class,launch());
        assertEquals(COMMAND.work(),ready.work());assertEquals(HEAD,ready.head());assertEquals(List.of("TEST-file"),ready.changedPaths());
        assertEquals(Map.of("INPUT",7L),ready.tokenUsage());assertTrue(ready.activeWallSeconds()>0);
    }
    @Test void aFinishedHeldBuildRetainsItsUnit(){assertInstanceOf(RunResult.RunWorkReady.class,launch());assertTrue(runtime.destroyed.isEmpty());}
    @Test void anAlreadyStoppedHeldBuildNeedsNoCancellation(){assertInstanceOf(RunResult.RunWorkReady.class,launch());assertTrue(runtime.cancelled.isEmpty());}
    @Test void heldCreationUsesTheFullPublicationBinding(){launch();assertEquals(new PublicationKey(COMMAND.work().publicationKey()),runtime.binding);}
    @Test void durableTopologyPrecedesResourceCreation(){
        launcher.launchHeld(COMMAND,RunObserver.IGNORING,unit->{assertNull(runtime.binding);runtime.lifecycle.add("persist-topology");});
        assertEquals(List.of("persist-topology","create-held","salvage"),runtime.lifecycle);
    }
    @Test void aTopologyWriteFailureCreatesNoResource(){
        var result=assertInstanceOf(RunResult.RunFailed.class,launcher.launchHeld(COMMAND,RunObserver.IGNORING,unit->{throw new IllegalStateException("TEST database unavailable");}));
        assertEquals("RUNTIME_UNAVAILABLE",result.cause());assertNull(runtime.binding);
    }
    @Test void aRuntimeWithoutRetentionCannotLaunch(){
        var ordinary=new RunLauncherTest.FakeRuntime();launcher.runtime=ordinary;
        var result=assertInstanceOf(RunResult.RunFailed.class,launch());assertEquals("BAD_COMMAND",result.cause());
        assertTrue(result.detail().contains("cannot retain publication"));assertTrue(ordinary.lifecycle.isEmpty());
    }
    @Test void anEscapedPushCannotBecomeReadiness(){
        runtime.publisherLines=List.of(CHECKPOINT,"{\"event\":\"pushed\",\"ref\":\"refs/heads/spire/TEST-held\"}");
        var failure=assertInstanceOf(RunResult.RunFailed.class,launch());assertEquals("PUBLISHER_FAILED",failure.cause());
        assertTrue(failure.detail().contains("escaped the initial hold"));assertEquals(Map.of("INPUT",7L),failure.tokenUsage());
    }
    @Test void noCheckpointCannotBecomeReadiness(){
        runtime.publisherLines=List.of();var result=assertInstanceOf(RunResult.RunFailed.class,launch());
        assertEquals("PUBLISHER_FAILED",result.cause());assertTrue(result.detail().contains("no observed checkpoint"));assertEquals(Map.of("INPUT",7L),result.tokenUsage());
    }
    @Test void aRefusedCheckpointCannotBecomeReadiness(){
        runtime.publisherLines=List.of(CHECKPOINT,"{\"event\":\"gate_refused\",\"blocked\":[{\"path\":\"TEST-file\",\"kind\":\"MODIFIED\"}]}");
        assertTrue(assertInstanceOf(RunResult.RunFinished.class,launch()).refused());
    }
    @Test void anUnobservedAgentCannotBecomeReadiness(){
        runtime.finalization=Finalization.faulted("TEST unknown agent");
        assertFalse(launch() instanceof RunResult.RunWorkReady);assertFalse(runtime.cancelled.isEmpty());
    }
    @Test void anUnobservedPushRetainsItsUncertainOutcome(){
        runtime.finalization=Finalization.faulted("TEST unknown agent");
        runtime.publisherLines=List.of(CHECKPOINT,"{\"event\":\"pushed\",\"ref\":\"refs/heads/spire/TEST-held\"}");
        var result=assertInstanceOf(RunResult.RunFinished.class,launch());
        assertTrue(result.agentUnobserved());assertEquals("refs/heads/spire/TEST-held",result.pushedRef());
        assertEquals(Map.of("INPUT",7L),result.tokenUsage());assertFalse(runtime.cancelled.isEmpty());
    }
}
