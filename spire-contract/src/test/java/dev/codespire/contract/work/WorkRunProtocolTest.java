package dev.codespire.contract.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.scm.RepoRef;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkRunProtocolTest {
    final UUID build=UUID.randomUUID(),delivery=UUID.randomUUID();
    final Instant issued=Instant.parse("2026-09-14T00:00:00Z"),expiry=issued.plusSeconds(300);
    WorkRunBinding binding(){return new WorkRunBinding("TEST-item",1,build,"a".repeat(64));}
    WorkPublicationPermit permit(){return new WorkPublicationPermit(binding(),delivery,"b".repeat(40),issued,expiry,List.of("TEST-protected/**"));}
    RunCommand.ExecuteRun execution(){return new RunCommand.ExecuteRun("TEST-run",new RepoRef("TEST-workspace","TEST-repo"),"https://forge.example.test/TEST-repo.git","main","c".repeat(40),"spire/TEST-build","TEST-private-prompt","TEST-harness","TEST-model","TEST-image",List.of(),300,"TEST-scm-ciphertext","TEST-harness-ciphertext");}
    ObjectMapper mapper(){return new ObjectMapper().registerModule(new JavaTimeModule());}

    @Test void aHeldBuildUsesADistinctDiscriminatorAndPreservesTheExecution() throws Exception {
        var command=new RunCommand.ExecuteWorkRun(execution(),binding());
        String json=mapper().writeValueAsString(command);
        assertEquals("ExecuteWorkRun",mapper().readTree(json).path("type").asText());
        assertEquals("TEST-run",mapper().readTree(json).path("runId").asText());
        assertEquals(command,mapper().readValue(json,RunCommand.class));
        assertEquals(execution().scmCredential(),command.scmCredential());assertEquals(execution().harnessCredential(),command.harnessCredential());
        assertFalse(command.toString().contains("TEST-scm-ciphertext"));assertFalse(command.toString().contains("TEST-harness-ciphertext"));assertFalse(command.toString().contains("TEST-private-prompt"));
    }
    @Test void heldBuildsCannotNameAnotherExecution(){assertThrows(IllegalArgumentException.class,()->new RunCommand.ExecuteWorkRun("TEST-other",execution(),binding()));}
    @Test void heldBuildsRequireExecutionAndWorkBinding(){assertThrows(NullPointerException.class,()->new RunCommand.ExecuteWorkRun("TEST-run",null,binding()));assertThrows(NullPointerException.class,()->new RunCommand.ExecuteWorkRun(execution(),null));}
    @Test void aFixRetainsItsStandaloneDiscriminatorAndExistingBranch() throws Exception {
        var command=execution().onExistingBranch("develop");String json=mapper().writeValueAsString(command);
        assertEquals("ExecuteRun",mapper().readTree(json).path("type").asText());
        assertEquals(command,mapper().readValue(json,RunCommand.class));assertTrue(command.existingBranch());assertEquals("develop",command.protectedBranch());
        assertThrows(IllegalArgumentException.class,()->new RunCommand.ExecuteWorkRun(command,binding()));
    }
    @Test void workIdentityAndGenerationAreRequired(){assertThrows(IllegalArgumentException.class,()->new WorkRunBinding(" ",1,build,"a".repeat(64)));assertThrows(IllegalArgumentException.class,()->new WorkRunBinding("TEST-item",0,build,"a".repeat(64)));}
    @Test void buildAttemptAndPreparationBindingAreRequired(){assertThrows(NullPointerException.class,()->new WorkRunBinding("TEST-item",1,null,"a".repeat(64)));for(String invalid:List.of("","a".repeat(63),"A".repeat(64)))assertThrows(IllegalArgumentException.class,()->new WorkRunBinding("TEST-item",1,build,invalid));}
    @Test void aPublicationPermitRoundTripsIncludingExpiredEvidence() throws Exception {
        var command=new RunCommand.PublishWorkRun("TEST-run",permit(),"TEST-rotated-scm-ciphertext");String json=mapper().writeValueAsString(command);
        assertEquals("PublishWorkRun",mapper().readTree(json).path("type").asText());
        assertEquals(command,mapper().readValue(json,RunCommand.class));assertFalse(command.permit().validAt(expiry.plusSeconds(1)));
        assertEquals("TEST-rotated-scm-ciphertext",command.scmCredential());assertNull(command.harnessCredential());
        assertFalse(command.toString().contains("TEST-rotated-scm-ciphertext"));
    }
    @Test void publicationRequiresItsRunPermitAndCredential(){assertThrows(IllegalArgumentException.class,()->new RunCommand.PublishWorkRun(" ",permit(),"TEST-ciphertext"));assertThrows(NullPointerException.class,()->new RunCommand.PublishWorkRun("TEST-run",null,"TEST-ciphertext"));assertThrows(IllegalArgumentException.class,()->new RunCommand.PublishWorkRun("TEST-run",permit()," "));}
    @Test void permitsRequireTheWorkAndDeliveryAttempt(){assertThrows(NullPointerException.class,()->new WorkPublicationPermit(null,delivery,"b".repeat(40),issued,expiry,List.of()));assertThrows(NullPointerException.class,()->new WorkPublicationPermit(binding(),null,"b".repeat(40),issued,expiry,List.of()));}
    @Test void aPermitRequiresTheFullCheckpointHead(){for(String invalid:List.of("","b".repeat(39),"B".repeat(40),"g".repeat(40)))assertThrows(IllegalArgumentException.class,()->new WorkPublicationPermit(binding(),delivery,invalid,issued,expiry,List.of()));}
    @Test void permitTimesAreRequiredAndOrdered(){assertThrows(NullPointerException.class,()->new WorkPublicationPermit(binding(),delivery,"b".repeat(40),null,expiry,List.of()));assertThrows(NullPointerException.class,()->new WorkPublicationPermit(binding(),delivery,"b".repeat(40),issued,null,List.of()));assertThrows(IllegalArgumentException.class,()->new WorkPublicationPermit(binding(),delivery,"b".repeat(40),issued,issued,List.of()));}
    @Test void permitsCannotRunEarlyOrAtTheirExpiry(){var value=permit();assertFalse(value.validAt(issued.minusNanos(1)));assertTrue(value.validAt(issued));assertTrue(value.validAt(expiry.minusNanos(1)));assertFalse(value.validAt(expiry));}
    @Test void protectedPathsAreRequiredAndCannotBeChangedAfterIssue(){assertThrows(NullPointerException.class,()->new WorkPublicationPermit(binding(),delivery,"b".repeat(40),issued,expiry,null));var paths=new ArrayList<>(List.of("TEST-protected/**"));var value=new WorkPublicationPermit(binding(),delivery,"b".repeat(40),issued,expiry,paths);paths.clear();assertEquals(List.of("TEST-protected/**"),value.protectedPaths());assertThrows(UnsupportedOperationException.class,()->value.protectedPaths().clear());}
    RunCommand.ExecuteRun onBranch(String branch){return new RunCommand.ExecuteRun("TEST-run",new RepoRef("TEST-workspace","TEST-repo"),"https://forge.example.test/TEST-repo.git",branch,"c".repeat(40),branch,"TEST-private-prompt","TEST-harness","TEST-model","TEST-image",List.of(),300,"TEST-scm-ciphertext","TEST-harness-ciphertext").onExistingBranch("TEST-main");}
    @Test void itemBuildsMayUseOnlyTheirOwnExistingBranch(){
        RunCommand.ExecuteRun own=onBranch("spire/work-TEST");
        assertDoesNotThrow(()->new RunCommand.ExecuteWorkRun(own,binding()));
        for(String foreign:List.of("feature/TEST","main","spire/TEST-build","spire/workTEST")){
            RunCommand.ExecuteRun other=onBranch(foreign);
            assertThrows(IllegalArgumentException.class,()->new RunCommand.ExecuteWorkRun(other,binding()),foreign);
        }
    }
    @Test void aPermitsExpectedHeadIsAFullSha(){
        for(String invalid:List.of("abc","","d".repeat(39),"D".repeat(40),"g".repeat(40)))assertThrows(IllegalArgumentException.class,()->permit().onExistingBranch(invalid,null),invalid);
        WorkPublicationPermit plain=permit();
        assertNull(plain.expectedRemoteHead());assertNull(plain.mergedBaseTip());assertFalse(plain.pushesToExistingBranch());
        WorkPublicationPermit existing=plain.onExistingBranch("d".repeat(40),"e".repeat(40));
        assertTrue(existing.pushesToExistingBranch());assertEquals("d".repeat(40),existing.expectedRemoteHead());assertEquals("e".repeat(40),existing.mergedBaseTip());
        assertEquals(plain.work(),existing.work());assertEquals(plain.deliveryAttemptId(),existing.deliveryAttemptId());assertEquals(plain.head(),existing.head());
        assertEquals(plain.issuedAt(),existing.issuedAt());assertEquals(plain.expiresAt(),existing.expiresAt());assertEquals(plain.protectedPaths(),existing.protectedPaths());
    }
    /** The held build gates the base merge before any permit exists, so it carries the same orchestrator-issued tip. */
    @Test void aLandFixBuildCarriesItsBaseTip() throws Exception {
        RunCommand.ExecuteWorkRun plain=new RunCommand.ExecuteWorkRun(onBranch("spire/work-TEST"),binding());
        assertNull(plain.mergedBaseTip());
        RunCommand.ExecuteWorkRun merged=plain.withMergedBaseTip("e".repeat(40));
        assertEquals("e".repeat(40),merged.mergedBaseTip());assertEquals(plain.execution(),merged.execution());assertEquals(plain.work(),merged.work());
        assertEquals(merged,mapper().readValue(mapper().writeValueAsString(merged),RunCommand.class));
        RunCommand.ExecuteRun fromBase=merged.execution().fromCheckpoint("TEST-previous","d".repeat(40)).fromBaseInstead("TEST note");
        assertEquals("e".repeat(40),merged.withExecution(fromBase).mergedBaseTip(),"a rebuilt execution keeps the tip");
        assertEquals(fromBase,merged.withExecution(fromBase).execution());
        for(String invalid:List.of("abc","E".repeat(40),"x".repeat(40)))assertThrows(IllegalArgumentException.class,()->plain.withMergedBaseTip(invalid),invalid);
        RunCommand.ExecuteWorkRun newBranch=new RunCommand.ExecuteWorkRun(execution(),binding());
        assertThrows(IllegalArgumentException.class,()->newBranch.withMergedBaseTip("e".repeat(40)),"only a run on the item's own branch merges its base");
    }
    @Test void aPermitsBaseTipNeedsAnExpectedHead(){
        assertThrows(IllegalArgumentException.class,()->permit().onExistingBranch(null,"e".repeat(40)));
        for(String invalid:List.of("abc","E".repeat(40),"x".repeat(40)))assertThrows(IllegalArgumentException.class,()->permit().onExistingBranch("d".repeat(40),invalid),invalid);
        assertDoesNotThrow(()->permit().onExistingBranch("d".repeat(40),null));
    }
    @Test void anExistingBranchPermitRoundTrips() throws Exception {
        RunCommand.PublishWorkRun command=new RunCommand.PublishWorkRun("TEST-run",permit().onExistingBranch("d".repeat(40),"e".repeat(40)),"TEST-rotated-scm-ciphertext");
        assertEquals(command,mapper().readValue(mapper().writeValueAsString(command),RunCommand.class));
    }
}
