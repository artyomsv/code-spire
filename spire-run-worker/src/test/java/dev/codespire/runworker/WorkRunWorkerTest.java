package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.work.WorkPublicationPermit;
import dev.codespire.runtime.*;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.*;
import static org.junit.jupiter.api.Assertions.*;

/** Discriminating worker decisions; persistence and containers have separate real integration proofs. */
class WorkRunWorkerTest {
    RunCommand.ExecuteWorkRun command=HeldRunLauncherTest.COMMAND;
    /** What the fake runtime says about the agent: null is "cannot tell". */
    Boolean agentRunning;
    final RunResult.RunWorkReady ready=new RunResult.RunWorkReady(command.runId(),command.work(),HeldRunLauncherTest.HEAD,List.of("TEST-file"),Map.of("INPUT",7L),9);
    final RunCommand.PublishWorkRun permit=new RunCommand.PublishWorkRun(command.runId(),new WorkPublicationPermit(command.work(),UUID.randomUUID(),ready.head(),Instant.now().minusSeconds(1),Instant.now().plusSeconds(60),List.of()),"TEST-current-scm");
    final List<String> events=new ArrayList<>();
    final List<RunResult> pending=new ArrayList<>(),reported=new ArrayList<>();
    RunResult terminal;
    String state="ready";
    boolean claim=true,permitAllowed=true,cancelled,claimFails,leaseAvailable=true,reportAccepted=true,present=true,held=true,lateCancel;
    int launches,publicationClaims,publications,deletions;
    boolean revoked;
    List<String> publisherLines=List.of("{\"event\":\"pushed\",\"ref\":\"refs/heads/spire/TEST-held\"}");
    Finalization finalization=Finalization.salvaged(0,"TEST exited");
    final WorkRunStore store=new WorkRunStore(){
        // These unit decisions supplement the durable-store and actual killed-JVM proof.
        @Override public boolean revoked(RunCommand.ExecuteWorkRun execution){return revoked;}
        @Override public boolean claim(RunCommand.ExecuteWorkRun execution){events.add("claim");if(claimFails)throw new IllegalStateException("TEST store unavailable");return claim;}
        @Override public Optional<Held> find(String id){return Optional.of(new Held(command,null,"TEST-unit",state,ready,terminal,permit,Instant.now().minusSeconds(120)));}
        @Override public void buildResult(RunResult result){events.add("build-result");pending.add(result);if(!(result instanceof RunResult.RunWorkReady))terminal=result;}
        @Override public void terminal(RunResult result){events.add("terminal");terminal=result;state="finished";pending.add(result);}
        @Override public List<RunResult> pendingResults(){return List.copyOf(pending);}
        @Override public void acknowledged(RunResult result){events.add("ack-result");pending.remove(result);}
        @Override public boolean claimPublication(RunCommand.PublishWorkRun request){publicationClaims++;if(permitAllowed)state="publishing";return permitAllowed;}
        @Override public void released(String id){events.add("released");}
        @Override public List<Held> unfinished(){return List.of(find(command.runId()).orElseThrow());}
        @Override public List<Held> awaitingRelease(){return List.of();}
        @Override public boolean claimPublicationRecovery(String id,Instant stale){return permitAllowed;}
        @Override public void abandonBuild(RunResult.RunFailed result){terminal(result);}
    };
    final WorkspaceLeases leases=new WorkspaceLeases(){
        @Override public boolean take(String id){return leaseAvailable;}
        @Override public void preserve(String id){events.add("preserve");}
        @Override public void release(String id){events.add("release-lease");}
        @Override public Optional<Instant> staleBefore(Duration duration){return Optional.of(Instant.now().minusSeconds(60));}
        @Override public Optional<Lease> find(String id){return Optional.empty();}
    };
    final class Runtime extends RunLauncherTest.FakeRuntime implements PublicationRuntime {
        @Override public List<RunHandle> discoverUnits(){return present?List.of(new RunHandle(command.runId(),"TEST-unit")):List.of();}
        @Override public boolean publicationHeld(RunHandle run){return present && held;}
        @Override public RunHandle createHeld(RunUnitSpec spec,PublicationKey key){throw new AssertionError("TEST publication must not create a build");}
        @Override public Finalization publishHeld(RunHandle run,PublicationKey key,UUID attempt,RunUnitSpec spec,Consumer<String> lines,BooleanSupplier allowed){
            publications++;assertEquals("publishing",state,"The durable claim must precede publisher IO");
            assertEquals(command.work().publicationKey(),key.value());assertEquals(permit.permit().deliveryAttemptId(),attempt);
            if(!allowed.getAsBoolean())lines.accept("{\"event\":\"failed\",\"cause\":\"PUBLICATION_CANCELLED\"}");else WorkRunWorkerTest.this.publisherLines.forEach(lines);
            if(lateCancel)WorkRunWorkerTest.this.cancelled=true;
            return WorkRunWorkerTest.this.finalization;
        }
        @Override public boolean agentRunning(RunHandle run){
            assertEquals(command.runId(),run.runId());
            if(agentRunning==null)throw new UnsupportedOperationException("TEST runtime cannot tell");
            return agentRunning;
        }
        @Override public void destroyHeld(RunHandle run,PublicationKey key){events.add("destroy");assertNotNull(terminal,"Record final evidence before deleting the workspace");deletions++;present=false;}
    }
    final Runtime runtime=new Runtime();
    final WorkRunWorker worker=new WorkRunWorker();
    RunResult buildResult=ready;
    @BeforeEach void wire(){
        worker.store=store;worker.leases=leases;worker.registry=new RunRegistry();worker.runtime=runtime;worker.staleAfterSeconds=60;
        worker.claims=new RunClaimStore(){@Override public boolean taken(String id,String slot){return cancelled;}};
        worker.launcher=new RunLauncher(){@Override public RunResult launchHeld(RunCommand.ExecuteWorkRun execution,RunObserver observer,Consumer<RunUnitSpec> saved){launches++;if(lateCancel)cancelled=true;return buildResult;}};
        worker.builder=new RunUnitBuilder(){@Override public RunUnitSpec publication(RunUnitSpec original,RunCommand.ExecuteWorkRun execution,RunCommand.PublishWorkRun request){return null; /* TEST runtime does not consume topology. */}};
        worker.failures=new RunFailures(){
            @Override public RunResult.RunFailed of(RunCommand.ExecuteRun execution,String cause,String detail){return new RunResult.RunFailed(execution.runId(),cause,detail,false,null);}
            // This decision fixture does not decrypt credentials; the focused publication tests below do.
            @Override public RunResult.RunFailed ofPublication(RunCommand.ExecuteRun execution,RunCommand.PublishWorkRun publication,String cause,String detail){return of(execution,cause,detail);}
        };
        worker.results=new RunResultReporter(){@Override public boolean report(RunResult result){reported.add(result);return reportAccepted;}};
    }
    @AfterEach void close(){worker.launcher.stopStreams();}
    void execute(){worker.execute(Message.of((RunCommand)command,()->{events.add("ack-command");return CompletableFuture.completedFuture(null);}),command).toCompletableFuture().join();}
    /** The same held build, paid by a signed-in seat that must be in use by {@code startBy}. */
    void paidBySignIn(Instant startBy){command=new RunCommand.ExecuteWorkRun(command.execution().paidBySignIn(startBy),command.work());}
    List<RunResult> agentStopped(){return reported.stream().filter(result->result instanceof RunResult.RunAgentStopped).toList();}

    /**
     * A command that waited past its start deadline never starts: its seat may serve another build by now,
     * and one sign-in serves one agent (review of PR #178). Nothing ran, so the seat is freed at once.
     */
    @Test void aSignInBuildPastItsStartDeadlineNeverStarts(){
        paidBySignIn(Instant.now().minusSeconds(1));agentRunning=false;
        execute();
        assertEquals(0,launches);
        assertEquals("BAD_COMMAND",assertInstanceOf(RunResult.RunFailed.class,terminal).cause());
        assertEquals(List.of(new RunResult.RunAgentStopped(command.runId())),agentStopped());
    }
    @Test void aSignInBuildInTimeStarts(){
        paidBySignIn(Instant.now().plusSeconds(600));agentRunning=false;
        execute();
        assertEquals(1,launches);
    }
    /** A seat is freed only on the runtime's word that the agent is not running — never on an outcome. */
    @Test void aSignInBuildReportsItsAgentStoppedOnlyWhenTheRuntimeKnows(){
        paidBySignIn(Instant.now().plusSeconds(600));
        agentRunning=true;execute();
        assertTrue(agentStopped().isEmpty(),"an agent that may still run keeps its seat");
    }
    @Test void aRuntimeThatCannotTellKeepsTheSeatHeld(){
        paidBySignIn(Instant.now().plusSeconds(600));
        agentRunning=null;execute();
        assertTrue(agentStopped().isEmpty());
    }
    @Test void aStoppedAgentFreesItsSeat(){
        paidBySignIn(Instant.now().plusSeconds(600));
        agentRunning=false;execute();
        assertEquals(List.of(new RunResult.RunAgentStopped(command.runId())),agentStopped());
    }
    @Test void anApiKeyBuildSaysNothingAboutSeats(){
        agentRunning=false;execute();
        assertTrue(agentStopped().isEmpty());
    }
    /** An abandoned build is stopped by recovery, and its seat freed once the runtime confirms it. */
    @Test void recoveryFreesTheSeatOfAnAbandonedSignInBuild(){
        paidBySignIn(Instant.now().plusSeconds(600));state="building";agentRunning=false;
        worker.recover();
        assertEquals(List.of(new RunResult.RunAgentStopped(command.runId())),agentStopped());
    }
    @Test void aFailedClaimCannotAcknowledgeTheCommand(){claimFails=true;assertThrows(IllegalStateException.class,this::execute);assertEquals(List.of("claim"),events);}
    @Test void anExecuteRedeliveryCannotRunTheHarnessAgain(){claim=false;execute();assertEquals(0,launches);assertTrue(reported.isEmpty());assertEquals(List.of("claim","ack-command"),events);}
    @Test void cancellationBeforeBuildBuysNoHarnessCall(){cancelled=true;execute();assertEquals(0,launches);assertEquals("CANCELLED",assertInstanceOf(RunResult.RunFailed.class,terminal).cause());}
    @Test void durableHoldBeforeBuildBuysNoHarnessCallWithoutM1Cancel(){revoked=true;assertFalse(cancelled);execute();assertEquals(0,launches);assertEquals("CANCELLED",assertInstanceOf(RunResult.RunFailed.class,terminal).cause());}
    @Test void durableHoldIsCheckedAgainAtPublisherStart(){revoked=true;assertFalse(cancelled);worker.publish(permit);assertEquals("CANCELLED",assertInstanceOf(RunResult.RunFailed.class,terminal).cause());assertEquals(0,deletions);}
    @Test void durableHoldRecoversReadyRunWithoutM1Cancel(){revoked=true;assertFalse(cancelled);worker.recover();assertEquals("CANCELLED",assertInstanceOf(RunResult.RunFailed.class,terminal).cause());assertEquals(0,publications);assertEquals(1,runtime.cancelled.size());}
    @Test void aFailedLeaseBuysNoHarnessCall(){leaseAvailable=false;execute();assertEquals(0,launches);assertEquals("WORKER_FAILED",assertInstanceOf(RunResult.RunFailed.class,terminal).cause());}
    @Test void cancellationAfterTheBuildRetainsItsMeasuredUsage(){lateCancel=true;execute();var failure=assertInstanceOf(RunResult.RunFailed.class,terminal);assertEquals("CANCELLED",failure.cause());assertEquals(ready.tokenUsage(),failure.tokenUsage());}
    @Test void aBrokerRefusalKeepsTheResultPending(){reportAccepted=false;execute();assertEquals(List.of(ready),pending);assertFalse(events.contains("ack-result"));}
    @Test void anotherDaemonCannotClaimPublication(){present=false;assertDoesNotThrow(()->worker.publish(permit));assertEquals(0,publicationClaims);assertEquals(0,publications);}
    @Test void anUnheldUnitCannotClaimPublication(){held=false;worker.publish(permit);assertEquals(0,publicationClaims);assertEquals(0,publications);}
    @Test void aRefusedPermitCannotReachThePublisher(){permitAllowed=false;worker.publish(permit);assertEquals(1,publicationClaims);assertEquals(0,publications);assertNull(terminal);}
    @Test void aSuccessfulPublicationPersistsBeforeDeletingAndReporting(){worker.publish(permit);assertEquals(1,publications);assertEquals(1,deletions);var finished=assertInstanceOf(RunResult.RunFinished.class,terminal);assertEquals(ready.tokenUsage(),finished.tokenUsage());assertEquals("refs/heads/spire/TEST-held",finished.pushedRef());assertTrue(events.indexOf("terminal")<events.indexOf("destroy"));assertTrue(events.contains("released"));assertTrue(events.contains("release-lease"));assertEquals(List.of(finished),reported);}
    @Test void anUnobservedPublisherRemainsRecoverable(){publisherLines=List.of();finalization=Finalization.faulted("TEST daemon unavailable");worker.publish(permit);assertEquals(1,publications);assertNull(terminal);assertEquals("publishing",state);assertEquals(0,deletions);assertTrue(reported.isEmpty());}
    @Test void aLateCancelCannotEraseAnObservedPush(){lateCancel=true;worker.publish(permit);assertEquals("refs/heads/spire/TEST-held",assertInstanceOf(RunResult.RunFinished.class,terminal).pushedRef());assertEquals(1,deletions);}
    @Test void aCancelledPermitRetainsTheWorkspaceAndUsage(){cancelled=true;worker.publish(permit);assertEquals("CANCELLED",assertInstanceOf(RunResult.RunFailed.class,terminal).cause());assertEquals(ready.tokenUsage(),((RunResult.RunFailed)terminal).tokenUsage());assertEquals(0,deletions);}
    @Test void aRefusedPublisherRetainsItsBlockedPaths(){publisherLines=List.of("{\"event\":\"gate_refused\",\"blocked\":[{\"path\":\"TEST-file\",\"kind\":\"MODIFY\"}]}");worker.publish(permit);var finished=assertInstanceOf(RunResult.RunFinished.class,terminal);assertTrue(finished.refused());assertEquals(List.of(new RunResult.BlockedChange("TEST-file","MODIFY")),finished.blocked());assertEquals(0,deletions);}
    @Test void aCancelledReadyRunIsRecoveredWithoutPublishing(){cancelled=true;worker.recover();assertEquals("CANCELLED",assertInstanceOf(RunResult.RunFailed.class,terminal).cause());assertEquals(ready.tokenUsage(),((RunResult.RunFailed)terminal).tokenUsage());assertEquals(0,publications);assertEquals(0,deletions);assertEquals(1,runtime.cancelled.size());}
    @Test void anAbandonedBuildIsNeverRebuilt(){state="building";worker.recover();assertEquals("SALVAGE_FAILED",assertInstanceOf(RunResult.RunFailed.class,terminal).cause());assertNull(((RunResult.RunFailed)terminal).tokenUsage());assertEquals(0,launches);assertEquals(0,publications);assertEquals(0,deletions);assertEquals(1,runtime.cancelled.size());}
    @Test void anExecutingRunIsNotRecoveredAsAnOrphan(){state="building";worker.registry.register(command.runId(),"TEST-harness",new RunHandle(command.runId(),"TEST-unit"),RunNotes.IGNORING);worker.recover();assertNull(terminal);assertTrue(runtime.cancelled.isEmpty());}
    @Test void publisherFailuresRedactTheRotatedCredentialBeforeReporting(){
        publicationFailureCredentials(false);worker.publish(permit);
        var failure=assertInstanceOf(RunResult.RunFailed.class,terminal);assertEquals("PUBLISHER_FAILED",failure.cause());
        assertFalse(failure.detail().contains("TEST-current-publisher-secret"));assertTrue(failure.detail().contains("TEST push failed"));
        assertEquals(ready.tokenUsage(),failure.tokenUsage());assertEquals(List.of(failure),reported);assertEquals(0,deletions);
    }
    @Test void unreadablePublicationCredentialsKeepTheErrorTextInsideRecovery(){
        publicationFailureCredentials(true);worker.publish(permit);
        assertEquals(1,publications);assertNull(terminal);assertEquals("publishing",state);assertTrue(reported.isEmpty());assertEquals(0,deletions);
    }
    void publicationFailureCredentials(boolean unreadable){
        assertNotEquals(command.execution().scmCredential(),permit.scmCredential(),"The old scrub must not accidentally contain the rotated credential");
        publisherLines=List.of("{\"event\":\"failed\",\"cause\":\"PUSH_FAILED\",\"detail\":\"TEST push failed: TEST-current-publisher-secret\"}");
        worker.failures=new RunFailures();worker.failures.enterprise=RunLauncherTest.noCorporateEnvironment();
        worker.failures.credentials=new Credentials(){
            @Override public Scm scm(String id,String packed){
                if(permit.scmCredential().equals(packed)){if(unreadable)throw new IllegalStateException("TEST key unavailable");return new Scm("TEST-bot","TEST-current-publisher-secret","TEST-bot","TEST-current-publisher-secret");}
                return new Scm("TEST-bot","TEST-original-secret","TEST-bot","TEST-original-secret");
            }
            @Override public Map<String,String> harnessEnv(String id,String packed){return Map.of();}
        };
    }
}
