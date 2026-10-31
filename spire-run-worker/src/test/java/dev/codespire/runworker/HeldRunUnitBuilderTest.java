package dev.codespire.runworker;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.*;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.work.*;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.harness.codex.CodexAdapter;
import dev.codespire.runtime.EgressPolicy;
import dev.codespire.runtime.RunUnitSpec;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class HeldRunUnitBuilderTest {
    final ObjectMapper mapper=new ObjectMapper();
    final EncryptionService encryption=new EncryptionService(EncryptionService.generateKeysetBase64());
    final WorkRunBinding binding=new WorkRunBinding("TEST-work",1,UUID.randomUUID(),"a".repeat(64));
    final RunUnitBuilder builder=new RunUnitBuilder();
    HeldRunUnitBuilderTest(){
        builder.credentials=new Credentials();builder.credentials.encryption=encryption;builder.credentials.mapper=mapper;
        builder.enterprise=RunLauncherTest.noCorporateEnvironment();builder.publisherImage="TEST-original-publisher";builder.maxWallClockSeconds=3600;
        builder.egressMode=EgressPolicy.Mode.OFF;
    }
    String scm(String id,String secret) throws Exception {return encryption.encryptString(mapper.writeValueAsString(new MachineAccountCredential("TEST-bot",secret)),RunCommand.scmCredentialAad(id));}
    RunCommand.ExecuteWorkRun command(String id,String harness) throws Exception {
        return new RunCommand.ExecuteWorkRun(new RunCommand.ExecuteRun(id,new RepoRef("TEST-work","app"),"https://forge.example.test/TEST/app.git","main","b".repeat(40),
                "spire/TEST-build","TEST task","codex","TEST-model","TEST-image",List.of("TEST-original/**"),60,scm(id,"TEST-original-secret"),harness),binding);
    }
    RunCommand.PublishWorkRun permit(String id,WorkRunBinding work) throws Exception {
        return new RunCommand.PublishWorkRun(id,new WorkPublicationPermit(work,UUID.randomUUID(),"c".repeat(40),Instant.parse("2026-09-14T01:00:00Z"),
                Instant.parse("2026-09-14T01:00:30Z"),List.of("TEST-current/**")),scm(id,"TEST-current-secret"));
    }
    @Test void heldTopologyUsesTheDistinctPublisher(){var unit=assertDoesNotThrow(()->builder.buildHeld(command("TEST-run",null),new CodexAdapter()));assertEquals(List.of("spire-publish-held"),unit.publisher().argv());}
    @Test void publicationRequiresTheRetainedUnitIdentity() throws Exception {
        var original=command("TEST-run",null);var held=builder.buildHeld(command("TEST-other",null),new CodexAdapter());var permit=permit(original.runId(),binding);
        assertThrows(IllegalArgumentException.class,()->builder.publication(held,original,permit));
    }
    @Test void publicationRequiresTheOriginalCommandIdentity() throws Exception {
        var original=command("TEST-other",null);var held=builder.buildHeld(command("TEST-run",null),new CodexAdapter());var permit=permit("TEST-run",binding);
        assertThrows(IllegalArgumentException.class,()->builder.publication(held,original,permit));
    }
    @Test void publicationRequiresTheOriginalWorkBinding() throws Exception {
        var original=command("TEST-run",null);var held=builder.buildHeld(original,new CodexAdapter());
        var permit=permit(original.runId(),new WorkRunBinding("TEST-other-item",1,binding.buildAttemptId(),binding.preparationBinding()));
        assertThrows(IllegalArgumentException.class,()->builder.publication(held,original,permit));
    }
    @Test void publicationDecryptsOnlyTheFreshScmCredential() throws Exception {
        var original=command("TEST-run",null);var held=builder.buildHeld(original,new CodexAdapter());var request=permit(original.runId(),binding);
        var noReusableHarness=command("TEST-run","TEST-invalid-old-harness-ciphertext");
        var resumed=assertDoesNotThrow(()->builder.publication(held,noReusableHarness,request));
        assertEquals("TEST-current-secret",resumed.publisher().environment().get("SPIRE_GIT_SECRET"));
        assertFalse(resumed.publisher().environment().containsValue("TEST-original-secret"));
        assertFalse(resumed.publisher().environment().containsValue("TEST-invalid-old-harness-ciphertext"));
    }
    @Test void publicationPreservesBothProtectedPathFloors() throws Exception {
        var original=command("TEST-run",null);var held=builder.buildHeld(original,new CodexAdapter());var resumed=builder.publication(held,original,permit(original.runId(),binding));
        assertEquals(Set.of("TEST-original/**","TEST-current/**"),Set.of(resumed.publisher().environment().get("SPIRE_PROTECTED_PATHS").split(",")));
    }
    @Test void theTrustedPublisherReceivesTheExactHeadAndLeaseWindow() throws Exception {
        var original=command("TEST-run",null);var held=builder.buildHeld(original,new CodexAdapter());var request=permit(original.runId(),binding);var resumed=builder.publication(held,original,request);
        assertEquals(List.of("spire-publish-permitted"),resumed.publisher().argv());
        assertEquals(request.permit().head(),resumed.publisher().environment().get("SPIRE_PERMITTED_HEAD"));
        assertEquals(request.permit().issuedAt().toString(),resumed.publisher().environment().get("SPIRE_PERMIT_ISSUED_AT"));
        assertEquals(request.permit().expiresAt().toString(),resumed.publisher().environment().get("SPIRE_PERMIT_EXPIRES_AT"));
    }
    @Test void publicationKeepsTheOriginalImageTopologyAndBudgets() throws Exception {
        var original=command("TEST-run",null);var held=builder.buildHeld(original,new CodexAdapter());builder.publisherImage="TEST-new-deployment-image";
        var resumed=builder.publication(held,original,permit(original.runId(),binding));
        assertEquals(held.publisher().image(),resumed.publisher().image());assertEquals(held.publisher().mounts(),resumed.publisher().mounts());
        assertEquals(held.init(),resumed.init());assertEquals(held.agent(),resumed.agent());assertEquals(held.enterprise(),resumed.enterprise());
        assertEquals(held.memoryBytes(),resumed.memoryBytes());assertEquals(held.nanoCpus(),resumed.nanoCpus());assertEquals(held.diskBytes(),resumed.diskBytes());assertEquals(held.wallClock(),resumed.wallClock());
    }
    /** A held build's agent is behind the proxy like any other, and resuming its publisher keeps the unit's egress, not today's setting. */
    @Test void publicationKeepsTheHeldUnitsEgress() throws Exception {
        builder.egressMode=EgressPolicy.Mode.REPORT;
        var original=command("TEST-run",null);var held=builder.buildHeld(original,new CodexAdapter());
        assertEquals(EgressPolicy.Mode.REPORT,held.egress().mode());
        builder.egressMode=EgressPolicy.Mode.OFF;
        var resumed=builder.publication(held,original,permit(original.runId(),binding));
        assertEquals(held.egress(),resumed.egress());
    }
    /** A land fix (M4 slice 7a): the item's own branch exists, at the approved head "b"x40, and its pull request targets TEST-main. */
    RunCommand.ExecuteWorkRun landFix(String id) throws Exception {
        return new RunCommand.ExecuteWorkRun(new RunCommand.ExecuteRun(id,new RepoRef("TEST-work","app"),"https://forge.example.test/TEST/app.git","spire/work-TEST","b".repeat(40),
                "spire/work-TEST","TEST task","codex","TEST-model","TEST-image",List.of("TEST-original/**"),60,scm(id,"TEST-original-secret"),null).onExistingBranch("TEST-main"),binding);
    }
    RunCommand.PublishWorkRun existingPermit(String id,String expected,String tip) throws Exception {
        RunCommand.PublishWorkRun plain=permit(id,binding);
        return new RunCommand.PublishWorkRun(id,plain.permit().onExistingBranch(expected,tip),plain.scmCredential());
    }
    @Test void anExistingBranchItemBuildFetchesThePullRequestBase() throws Exception {
        RunUnitSpec unit=builder.buildHeld(landFix("TEST-run"),new CodexAdapter());
        assertEquals("TEST-main",unit.init().environment().get("SPIRE_FETCH_BRANCH"));
        assertEquals("existing",unit.publisher().environment().get("SPIRE_BRANCH_MODE"));
        assertFalse(unit.agent().environment().containsKey("SPIRE_FETCH_BRANCH"));
    }
    /** A build that starts from a checkpoint tells its held publisher where, to refuse a head off that chain (M4 slice 3, A2). */
    @Test void theHeldPublisherIsToldTheStartHead() throws Exception {
        RunCommand.ExecuteWorkRun plain=command("TEST-run",null);
        RunCommand.ExecuteWorkRun step=new RunCommand.ExecuteWorkRun(plain.execution().fromCheckpoint("TEST-previous","c".repeat(40)),binding);
        RunUnitSpec unit=builder.buildHeld(step,new CodexAdapter());
        assertEquals("c".repeat(40),unit.publisher().environment().get("SPIRE_START_HEAD"));
        assertFalse(unit.agent().environment().containsKey("SPIRE_START_HEAD"),"the agent is not told");
        assertFalse(builder.buildHeld(plain,new CodexAdapter()).publisher().environment().containsKey("SPIRE_START_HEAD"),"a build from the base has no start head");
        RunUnitSpec fix=builder.buildHeld(new RunCommand.ExecuteWorkRun(landFix("TEST-run").execution().fromCheckpoint("TEST-previous","c".repeat(40)),binding),new CodexAdapter());
        assertEquals("c".repeat(40),fix.publisher().environment().get("SPIRE_START_HEAD"),"on an existing branch too");
    }
    /** Only a plan step may finish empty (M4 slice 3, decision 14): its held publisher alone is told so. */
    @Test void onlyAnEmptyAllowedRunTellsThePublisher() throws Exception {
        RunUnitSpec step=builder.buildHeld(command("TEST-run",null).allowingEmpty(),new CodexAdapter());
        assertEquals("1",step.publisher().environment().get("SPIRE_EMPTY_ALLOWED"));
        assertFalse(step.agent().environment().containsKey("SPIRE_EMPTY_ALLOWED"),"the agent is not told");
        assertFalse(builder.buildHeld(command("TEST-run",null),new CodexAdapter()).publisher().environment().containsKey("SPIRE_EMPTY_ALLOWED"),
                "an ordinary held build keeps today's missing-checkpoint failure");
        assertFalse(builder.buildHeld(landFix("TEST-run"),new CodexAdapter()).publisher().environment().containsKey("SPIRE_EMPTY_ALLOWED"),
                "a land fix is never empty");
    }
    @Test void aNewBranchItemBuildFetchesNothingExtra() throws Exception {
        RunUnitSpec unit=builder.buildHeld(command("TEST-run",null),new CodexAdapter());
        assertFalse(unit.init().environment().containsKey("SPIRE_FETCH_BRANCH"));
    }
    @Test void anExpectedHeadPermitNeedsAnExistingBranchRunFromThatHead() throws Exception {
        RunCommand.ExecuteWorkRun original=landFix("TEST-run");RunUnitSpec held=builder.buildHeld(original,new CodexAdapter());
        RunCommand.PublishWorkRun otherHead=existingPermit(original.runId(),"d".repeat(40),null);
        assertThrows(IllegalArgumentException.class,()->builder.publication(held,original,otherHead));
        RunCommand.ExecuteWorkRun merging=original.withMergedBaseTip("e".repeat(40));RunUnitSpec heldMerging=builder.buildHeld(merging,new CodexAdapter());
        RunUnitSpec resumed=builder.publication(heldMerging,merging,existingPermit(original.runId(),"b".repeat(40),"e".repeat(40)));
        assertEquals("b".repeat(40),resumed.publisher().environment().get("SPIRE_EXPECTED_REMOTE_HEAD"));
        assertEquals("e".repeat(40),resumed.publisher().environment().get("SPIRE_MERGED_BASE_TIP"));
        RunUnitSpec noTip=builder.publication(held,original,existingPermit(original.runId(),"b".repeat(40),null));
        assertFalse(noTip.publisher().environment().containsKey("SPIRE_MERGED_BASE_TIP"));
        RunCommand.ExecuteWorkRun fresh=command("TEST-run",null);RunUnitSpec freshHeld=builder.buildHeld(fresh,new CodexAdapter());
        RunCommand.PublishWorkRun onANewBranch=existingPermit(fresh.runId(),"b".repeat(40),null);
        assertThrows(IllegalArgumentException.class,()->builder.publication(freshHeld,fresh,onANewBranch),"a new branch has no remote head to expect");
        assertFalse(builder.publication(freshHeld,fresh,permit(fresh.runId(),binding)).publisher().environment().containsKey("SPIRE_EXPECTED_REMOTE_HEAD"));
    }
    /** The held build gates the base merge first: its publisher must excuse the same base tip the permit will. */
    @Test void aLandFixsHeldPublisherReceivesTheBaseTip() throws Exception {
        RunUnitSpec merging=builder.buildHeld(landFix("TEST-run").withMergedBaseTip("e".repeat(40)),new CodexAdapter());
        assertEquals("e".repeat(40),merging.publisher().environment().get("SPIRE_MERGED_BASE_TIP"));
        assertEquals(List.of("spire-publish-held"),merging.publisher().argv());
        assertFalse(merging.agent().environment().containsKey("SPIRE_MERGED_BASE_TIP"));
        RunUnitSpec failedChecks=builder.buildHeld(landFix("TEST-run"),new CodexAdapter());
        assertFalse(failedChecks.publisher().environment().containsKey("SPIRE_MERGED_BASE_TIP"));
    }
    @Test void aPermitMustExcuseTheSameBaseTipAsTheHeldBuild() throws Exception {
        RunCommand.ExecuteWorkRun merging=landFix("TEST-run").withMergedBaseTip("e".repeat(40));RunUnitSpec held=builder.buildHeld(merging,new CodexAdapter());
        RunCommand.PublishWorkRun otherTip=existingPermit(merging.runId(),"b".repeat(40),"f".repeat(40));
        assertThrows(IllegalArgumentException.class,()->builder.publication(held,merging,otherTip));
        RunCommand.PublishWorkRun noTip=existingPermit(merging.runId(),"b".repeat(40),null);
        assertThrows(IllegalArgumentException.class,()->builder.publication(held,merging,noTip),"the checkpoint was gated with the tip");
        RunCommand.ExecuteWorkRun failedChecks=landFix("TEST-run");RunUnitSpec heldChecks=builder.buildHeld(failedChecks,new CodexAdapter());
        RunCommand.PublishWorkRun widened=existingPermit(failedChecks.runId(),"b".repeat(40),"e".repeat(40));
        assertThrows(IllegalArgumentException.class,()->builder.publication(heldChecks,failedChecks,widened),"a permit cannot widen the held build's gate");
    }
    @Test void anExistingBranchRunNeedsAnExpectedHead() throws Exception {
        RunCommand.ExecuteWorkRun original=landFix("TEST-run");RunUnitSpec held=builder.buildHeld(original,new CodexAdapter());
        RunCommand.PublishWorkRun plain=permit(original.runId(),binding);
        assertThrows(IllegalArgumentException.class,()->builder.publication(held,original,plain));
    }
}
