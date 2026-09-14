package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.event.RunResult;
import dev.codespire.contract.work.WorkPublicationPermit;
import dev.codespire.contract.work.WorkRunBinding;
import dev.codespire.runtime.*;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.*;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class WorkRunStoreTest {
    @Inject WorkRunStore store;
    @Inject RunClaimStore claims;
    @Inject DataSource dataSource;
    @Inject com.fasterxml.jackson.databind.ObjectMapper mapper;
    @Inject dev.codespire.encryption.EncryptionService encryption;
    final List<String> ids = new ArrayList<>();

    @AfterEach void deleteExactFixtures() throws Exception {
        try(Connection c=dataSource.getConnection()) {
            for(String id:ids) {
                try(PreparedStatement ps=c.prepareStatement("DELETE FROM runworker.work_publication_revocation WHERE run_id=?")){ps.setString(1,id);ps.executeUpdate();}
                try(PreparedStatement ps=c.prepareStatement("DELETE FROM runworker.work_run WHERE run_id=?")){ps.setString(1,id);ps.executeUpdate();}
                try(PreparedStatement ps=c.prepareStatement("DELETE FROM runworker.run_claim WHERE run_id=?")){ps.setString(1,id);ps.executeUpdate();}
                try(PreparedStatement ps=c.prepareStatement("DELETE FROM runworker.run_lease WHERE run_id=?")){ps.setString(1,id);ps.executeUpdate();}
            }
        }
    }

    @Test void revocationCanCommitBeforeExecutionAndRedeliverIdempotently() {
        var command=command();var hold=new RunCommand.HoldWorkRun(command.runId(),command.work());
        store.revoke(hold);store.revoke(hold);assertTrue(store.find(command.runId()).isEmpty());
        assertTrue(store.revoked(command));assertFalse(claims.taken(command.runId(),RunDispatcher.CANCEL_SLOT));
        assertTrue(store.claim(command));assertTrue(store.revoked(store.find(command.runId()).orElseThrow().execution()));
    }
    @Test void anotherBuildBindingCannotRevokeThisRun() {
        var command=command();var original=command.work();
        var other=new WorkRunBinding(original.workItemId(),original.generation()+1,original.buildAttemptId(),original.preparationBinding());
        store.revoke(new RunCommand.HoldWorkRun(command.runId(),other));assertFalse(store.revoked(command));
        store.claim(command);store.saveUnit(command.runId(),unit(command.runId()));store.buildResult(ready(command));
        assertTrue(store.claimPublication(permit(command,command.work(),"b".repeat(40),Instant.now().minusSeconds(1),Instant.now().plusSeconds(60))));
    }

    @Test void claimAndEncryptedExecutionAreOneDurableDecision() throws Exception {
        var command=command();
        assertTrue(store.claim(command));
        assertTrue(claims.taken(command.runId(),RunDispatcher.EXECUTE_SLOT));
        assertFalse(store.claim(command));
        assertEquals(command,store.find(command.runId()).orElseThrow().execution());
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("SELECT execution FROM runworker.work_run WHERE run_id=?")) {
            ps.setString(1,command.runId());
            try(ResultSet row=ps.executeQuery()) {assertTrue(row.next());assertFalse(new String(row.getBytes(1),java.nio.charset.StandardCharsets.UTF_8).contains("TEST-private-prompt"));}
        }
    }

    @Test void anExistingStandaloneClaimCannotBecomeAHeldBuild() {
        var command=command();
        assertTrue(claims.claim(command.runId(),RunDispatcher.EXECUTE_SLOT));
        assertFalse(store.claim(command));
        assertTrue(store.find(command.runId()).isEmpty());
    }

    @Test void topologyIsRetainedOnceWithItsOriginalLimitsAndEnvironment() {
        var command=command();store.claim(command);
        var unit=unit(command.runId());store.saveUnit(command.runId(),unit);
        assertEquals(unit,store.find(command.runId()).orElseThrow().unit());
        assertThrows(IllegalStateException.class,()->store.saveUnit(command.runId(),unit));
        assertEquals(unit,store.find(command.runId()).orElseThrow().unit());
    }

    @Test void topologyMustNameItsClaimedRun() {
        var command=command();store.claim(command);
        assertThrows(IllegalArgumentException.class,()->store.saveUnit(command.runId(),unit("TEST-other-run")));
        assertNull(store.find(command.runId()).orElseThrow().unit());
    }

    @Test void readyAndFinalResultsHaveSeparateRecoverableAcknowledgments() {
        var command=prepared();var ready=ready(command);store.buildResult(ready);
        assertEquals("ready",store.find(command.runId()).orElseThrow().state());
        assertEquals(List.of(ready),pending(command.runId()));
        // A second store instance represents lost worker memory; data and its encrypted decode remain real.
        WorkRunStore restarted=new WorkRunStore();restarted.dataSource=dataSource;restarted.mapper=mapper;restarted.encryption=encryption;
        assertEquals(ready,restarted.find(command.runId()).orElseThrow().ready());
        var finished=new RunResult.RunFinished(command.runId(),"refs/heads/spire/TEST-build",ready.changedPaths(),List.of(),ready.tokenUsage(),false);
        restarted.terminal(finished);
        assertEquals(List.of(ready,finished),pending(command.runId()));
        restarted.acknowledged(ready);
        assertEquals(List.of(finished),pending(command.runId()),"a ready ack cannot consume the terminal result");
        restarted.acknowledged(finished);
        assertEquals(List.of(),pending(command.runId()));
        restarted.terminal(new RunResult.RunFailed(command.runId(),"WORKER_FAILED","TEST late duplicate",false,null));
        assertEquals(finished,restarted.find(command.runId()).orElseThrow().terminal(),"first terminal evidence remains stable");
    }

    @Test void aReadyResultCannotClaimAnotherPreparation() {
        var command=prepared();
        var wrong=new WorkRunBinding(command.work().workItemId(),command.work().generation(),command.work().buildAttemptId(),"f".repeat(64));
        assertThrows(IllegalStateException.class,()->store.buildResult(new RunResult.RunWorkReady(command.runId(),wrong,"b".repeat(40),List.of(),null,1)));
        assertEquals("building",store.find(command.runId()).orElseThrow().state());
        assertTrue(pending(command.runId()).isEmpty());
    }

    @Test void readinessCannotPrecedeTheRetainedTopology() {
        var command=command();store.claim(command);
        assertThrows(IllegalStateException.class,()->store.buildResult(ready(command)));
        assertEquals("building",store.find(command.runId()).orElseThrow().state());
        assertTrue(pending(command.runId()).isEmpty());
    }

    @Test void publicationClaimCommitsThePermitBeforeAnyIo() {
        var command=prepared();store.buildResult(ready(command));
        var request=permit(command,command.work(),"b".repeat(40),Instant.now().minusSeconds(5),Instant.now().plusSeconds(60));
        assertTrue(store.claimPublication(request));
        var persisted=store.find(command.runId()).orElseThrow();
        assertEquals("publishing",persisted.state());assertEquals(request,persisted.permit());
        assertFalse(store.claimPublication(request),"a duplicate observes the claimed publisher; it cannot claim a second one");
    }

    @Test void aPermitNeedsTheMatchingGenerationBuildAndHead() {
        var command=prepared();store.buildResult(ready(command));
        List<WorkRunBinding> wrong=List.of(
                new WorkRunBinding("TEST-another-item",1,command.work().buildAttemptId(),command.work().preparationBinding()),
                new WorkRunBinding(command.work().workItemId(),2,command.work().buildAttemptId(),command.work().preparationBinding()),
                new WorkRunBinding(command.work().workItemId(),1,UUID.randomUUID(),command.work().preparationBinding()),
                new WorkRunBinding(command.work().workItemId(),1,command.work().buildAttemptId(),"c".repeat(64)));
        for(var binding:wrong)assertFalse(store.claimPublication(permit(command,binding,"b".repeat(40),Instant.now().minusSeconds(5),Instant.now().plusSeconds(60))));
        assertFalse(store.claimPublication(permit(command,command.work(),"c".repeat(40),Instant.now().minusSeconds(5),Instant.now().plusSeconds(60))));
        assertEquals("ready",store.find(command.runId()).orElseThrow().state());
        assertNull(store.find(command.runId()).orElseThrow().permit());
    }

    @Test void expiredOrFuturePermitsCannotClaimPublication() {
        var command=prepared();store.buildResult(ready(command));
        assertFalse(store.claimPublication(permit(command,command.work(),"b".repeat(40),Instant.now().minusSeconds(60),Instant.now().minusSeconds(1))));
        assertFalse(store.claimPublication(permit(command,command.work(),"b".repeat(40),Instant.now().plusSeconds(60),Instant.now().plusSeconds(120))));
        assertEquals("ready",store.find(command.runId()).orElseThrow().state());
        assertNull(store.find(command.runId()).orElseThrow().permit());
    }

    @Test void aDurableCancellationBlocksAnOtherwiseValidPermit() {
        var command=prepared();store.buildResult(ready(command));
        claims.claim(command.runId(),RunDispatcher.CANCEL_SLOT);
        assertFalse(store.claimPublication(permit(command,command.work(),"b".repeat(40),Instant.now().minusSeconds(5),Instant.now().plusSeconds(60))));
        assertEquals("ready",store.find(command.runId()).orElseThrow().state());
        assertNull(store.find(command.runId()).orElseThrow().permit());
    }

    @Test void recoveryCannotTakeAFreshPublicationOwner() throws Exception {
        var command=prepared();store.buildResult(ready(command));
        assertTrue(store.claimPublication(permit(command,command.work(),"b".repeat(40),Instant.now().minusSeconds(1),Instant.now().plusSeconds(60))));
        assertFalse(store.claimPublicationRecovery(command.runId(),Instant.now().minusSeconds(60)));
        try(var c=dataSource.getConnection();var ps=c.prepareStatement("UPDATE runworker.run_lease SET preserved_at=now() WHERE run_id=?")) {
            ps.setString(1,command.runId());assertEquals(1,ps.executeUpdate());
        }
        assertTrue(store.claimPublicationRecovery(command.runId(),Instant.now().minusSeconds(60)));
        assertFalse(store.claimPublicationRecovery(command.runId(),Instant.now().minusSeconds(60)),"Taking recovery clears the preserved marker");
    }
    @Test void recoveryCanTakeADeadPublicationOwner() throws Exception {
        var command=prepared();store.buildResult(ready(command));
        assertTrue(store.claimPublication(permit(command,command.work(),"b".repeat(40),Instant.now().minusSeconds(1),Instant.now().plusSeconds(60))));
        try(var c=dataSource.getConnection();var ps=c.prepareStatement("UPDATE runworker.run_lease SET heartbeat_at=now()-interval '2 minutes' WHERE run_id=?")) {
            ps.setString(1,command.runId());assertEquals(1,ps.executeUpdate());
        }
        assertTrue(store.claimPublicationRecovery(command.runId(),Instant.now().minusSeconds(60)));
        assertFalse(store.claimPublicationRecovery(command.runId(),Instant.now().minusSeconds(60)),"Taking recovery refreshes the heartbeat");
    }
    @Test void anUnclaimedReadyBuildCannotBecomePublicationRecovery() {
        var command=prepared();store.buildResult(ready(command));
        assertFalse(store.claimPublicationRecovery(command.runId(),Instant.now()));
        assertEquals("ready",store.find(command.runId()).orElseThrow().state());
    }
    @Test void staleBuildRecoveryCannotOverwriteCommittedReadiness() {
        var command=prepared();store.buildResult(ready(command));
        store.abandonBuild(new RunResult.RunFailed(command.runId(),"SALVAGE_FAILED","TEST old observation",false,null));
        assertEquals("ready",store.find(command.runId()).orElseThrow().state());assertNull(store.find(command.runId()).orElseThrow().terminal());
    }
    @Test void readinessIsWrittenOnlyOnce() {
        var command=prepared();var original=ready(command);store.buildResult(original);
        assertThrows(IllegalStateException.class,()->store.buildResult(new RunResult.RunWorkReady(command.runId(),command.work(),"c".repeat(40),List.of(),null,2)));
        assertEquals(original,store.find(command.runId()).orElseThrow().ready());
    }
    @Test void onlyObservedSuccessfulPublicationSchedulesDeletion() {
        var command=prepared();store.buildResult(ready(command));
        store.terminal(new RunResult.RunFinished(command.runId(),"refs/heads/spire/TEST-build",List.of(),List.of(),Map.of("INPUT",7L),false));
        assertTrue(store.awaitingRelease().stream().anyMatch(held->held.execution().runId().equals(command.runId())));
        store.released(command.runId());assertTrue(store.awaitingRelease().stream().noneMatch(held->held.execution().runId().equals(command.runId())));
        for(int shape=0;shape<3;shape++) {
            var other=prepared();store.buildResult(ready(other));
            var terminal=new RunResult.RunFinished(other.runId(),shape==1?"refs/heads/spire/TEST-build":null,List.of(),
                    shape==2?List.of(new RunResult.BlockedChange("TEST-protected","MODIFY")):List.of(),Map.of("INPUT",7L),false).withAgentUnobserved(shape==1);
            store.terminal(terminal);
            assertTrue(store.awaitingRelease().stream().noneMatch(held->held.execution().runId().equals(other.runId())),"Unsafe terminal shape "+shape+" must retain work");
        }
    }

    private List<RunResult> pending(String id){return store.pendingResults().stream().filter(r->r.runId().equals(id)).toList();}
    private RunCommand.ExecuteWorkRun prepared(){var command=command();store.claim(command);store.saveUnit(command.runId(),unit(command.runId()));return command;}
    private RunResult.RunWorkReady ready(RunCommand.ExecuteWorkRun command){return new RunResult.RunWorkReady(command.runId(),command.work(),"b".repeat(40),List.of("TEST-file"),Map.of("INPUT",7L),12);}
    private RunCommand.PublishWorkRun permit(RunCommand.ExecuteWorkRun command,WorkRunBinding work,String head,Instant issued,Instant expiry){return new RunCommand.PublishWorkRun(command.runId(),new WorkPublicationPermit(work,UUID.randomUUID(),head,issued,expiry,List.of("TEST-protected/**")),"TEST-rotated-ciphertext");}
    private RunCommand.ExecuteWorkRun command() {
        String id="TEST-work-store-"+UUID.randomUUID();ids.add(id);
        var execution=new RunCommand.ExecuteRun(id,new dev.codespire.contract.scm.RepoRef("TEST-workspace","TEST-repo"),
                "https://example.invalid/TEST/repo.git","main","a".repeat(40),"spire/TEST-build",
                "TEST-private-prompt","TEST-harness","TEST-model","TEST-agent-image",List.of(),60,"TEST-scm-ciphertext","TEST-harness-ciphertext");
        return new RunCommand.ExecuteWorkRun(execution,new WorkRunBinding("TEST-item",1,UUID.randomUUID(),"a".repeat(64)));
    }
    private RunUnitSpec unit(String id) {
        var part=new ContainerSpec("TEST-image",List.of("TEST-command"),Map.of("TEST_SECRET","TEST-private-key"),List.of());
        return new RunUnitSpec(id,part,part,part,EnterpriseEnvironment.NONE,64*1024*1024,500_000_000,16*1024*1024,Duration.ofSeconds(60));
    }
}
