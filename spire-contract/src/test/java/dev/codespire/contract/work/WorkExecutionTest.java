package dev.codespire.contract.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import dev.codespire.contract.scm.PullRequestRef;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class WorkExecutionTest {
    final WorkRunBinding binding=new WorkRunBinding("TEST-work",1,UUID.randomUUID(),"a".repeat(64));
    final WorkExecution built=new WorkExecution("TEST-run",binding,"b".repeat(40),null,null,null);
    @Test void aBlankRunCannotNameAnExecution(){assertThrows(IllegalArgumentException.class,()->new WorkExecution(" ",binding,built.head(),null,null,null));}
    @Test void aMissingBindingIsRejected(){assertThrows(NullPointerException.class,()->new WorkExecution("TEST-run",null,built.head(),null,null,null));}
    @Test void aPartialHeadCannotNameVerifiedWork(){assertThrows(IllegalArgumentException.class,()->new WorkExecution("TEST-run",binding,"abcdef1",null,null,null));}
    @Test void aBlankReviewCannotNameEvidence(){assertThrows(IllegalArgumentException.class,()->new WorkExecution("TEST-run",binding,built.head(),null,null," "));}
    @Test void aVerificationWitherRequiresItsAttempt(){assertThrows(NullPointerException.class,()->built.verified((UUID)null));}
    @Test void aDeliveryWitherRequiresItsPullRequest(){assertThrows(NullPointerException.class,()->built.delivered(null));}
    @Test void aReviewWitherRequiresItsReview(){assertThrows(NullPointerException.class,()->built.reviewed(null));}
    @Test void phaseProofsPreserveTheBuildAndEarlierEvidence(){
        UUID verification=UUID.randomUUID();var pr=new PullRequestRef(1,"https://forge.example.test/TEST/1",true);
        assertEquals(new WorkExecution("TEST-run",binding,built.head(),verification,null,pr,"TEST-review"),built.verified(verification).delivered(pr).reviewed("TEST-review"));
    }
    @Test void reservationAndUsageChangesPreserveExecution(){
        var progress=WorkProgress.empty().withExecution(built);
        assertEquals(built,progress.reserve(true).execution());assertEquals(built,progress.unknownUsage().execution());
        assertEquals(built,progress.finish(7,2,1).execution());assertEquals(built,progress.start(UUID.randomUUID(),"verify",Instant.EPOCH).execution());
    }
    @Test void aNewBuildCannotReuseEarlierVerification(){
        var progress=WorkProgress.empty().withExecution(built.verified(UUID.randomUUID()));
        assertNull(progress.start(UUID.randomUUID(),"build",Instant.EPOCH).execution());
    }
    @Test void lateUsagePreservesTheCurrentAttemptAndItsExecution(){
        var progress=WorkProgress.empty().start(UUID.randomUUID(),"build",Instant.EPOCH).withExecution(built);
        var charged=progress.account(7,2,1);assertEquals(progress.attemptId(),charged.attemptId());assertEquals("started",charged.attemptState());
        assertTrue(charged.reserved());assertEquals(built,charged.execution());assertEquals(progress.runs(),charged.runs());assertEquals(progress.steps(),charged.steps());
        assertEquals(7,charged.wallSeconds());assertEquals(2,charged.costMillicents());assertEquals(1,charged.calls());
        assertThrows(IllegalArgumentException.class,()->progress.account(-1,0,0));assertThrows(IllegalArgumentException.class,()->progress.account(0,-1,0));assertThrows(IllegalArgumentException.class,()->progress.account(0,0,-1));
    }
    @Test void verificationEvidenceIsCarriedBesideTheOldAttempt(){
        var result=new WorkVerification(UUID.randomUUID(),built.head(),WorkVerification.Outcome.PASSED,null,
                java.util.List.of(new WorkVerification.CheckResult("TEST-check",0,1,"")));
        var verified=built.verified(result);
        assertEquals(result.attemptId(),verified.verificationAttempt());assertEquals(result,verified.verification());
        assertEquals(result,verified.delivered(new PullRequestRef(1,"https://forge.example.test/TEST/1",true)).reviewed("TEST-review").verification());
    }
    @Test void aVerificationOfAnotherHeadIsRefused(){
        var other=new WorkVerification(UUID.randomUUID(),"c".repeat(40),WorkVerification.Outcome.UNVERIFIED,"timed_out",java.util.List.of());
        assertThrows(IllegalArgumentException.class,()->built.verified(other));
    }
    @Test void anExecutionStoredBeforeVerificationExistedStillReads() throws Exception {
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule());
        var json=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(built.verified(UUID.randomUUID()));
        json.remove("verification");
        var read=mapper.treeToValue(json,WorkExecution.class);
        assertNotNull(read.verificationAttempt());assertNull(read.verification());
    }
    @Test void executionSurvivesAStoredProgressRoundTrip() throws Exception {
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule());
        var progress=WorkProgress.empty().withExecution(built.verified(UUID.randomUUID()));
        assertEquals(progress,mapper.readValue(mapper.writeValueAsBytes(progress),WorkProgress.class));
        assertNull(mapper.readValue("{}",WorkProgress.class).execution());
    }
    final PullRequestRef pr42=new PullRequestRef(42,"https://forge.example.test/TEST/42",false);
    // Review of slice 9: a merge the factory did not make names no method, because GitHub's pull request read reports none.
    @Test void aLandingMayNameNoKnownMethod(){
        WorkLanding landing=new WorkLanding(new PullRequestRef(7,"https://forge.example.test/TEST/7",false),"c".repeat(40),"unknown","TEST-900777",Instant.EPOCH);
        assertEquals("unknown",landing.method());
        assertThrows(IllegalArgumentException.class,()->new WorkLanding(landing.pullRequest(),"c".repeat(40),"TEST-octopus","TEST-900777",Instant.EPOCH));
    }
    WorkLanding landing(long number){return new WorkLanding(new PullRequestRef(number,"https://forge.example.test/TEST/"+number,false),"c".repeat(40),"squash",WorkLanding.FACTORY,Instant.EPOCH);}
    @Test void landingIsCarriedThroughEveryWither(){
        var landed=built.delivered(pr42).landed(landing(42));
        assertEquals(landing(42),landed.landing());
        assertEquals(landing(42),landed.reviewed("TEST-review").landing());
        assertEquals(landing(42),landed.delivered(pr42).landing());
        assertEquals(landing(42),landed.verified(UUID.randomUUID()).landing());
        var result=new WorkVerification(UUID.randomUUID(),built.head(),WorkVerification.Outcome.PASSED,null,
                java.util.List.of(new WorkVerification.CheckResult("TEST-check",0,1,"")));
        assertEquals(landing(42),landed.verified(result).landing());
    }
    @Test void aLandingOfAnotherPullRequestIsRefused(){
        assertThrows(IllegalArgumentException.class,()->built.delivered(pr42).landed(landing(43)));
        assertThrows(IllegalArgumentException.class,()->new WorkExecution("TEST-run",binding,built.head(),null,null,pr42,null,landing(43)));
        assertThrows(IllegalArgumentException.class,()->built.landed(landing(42)),"a landing needs a delivered pull request");
    }
    @Test void anExecutionStoredBeforeLandingStillReads() throws Exception {
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule());
        var json=(com.fasterxml.jackson.databind.node.ObjectNode)mapper.valueToTree(built.delivered(pr42));
        json.remove("landing");
        assertNull(mapper.treeToValue(json,WorkExecution.class).landing());
    }
    @Test void aLandingSurvivesAStoredRoundTrip() throws Exception {
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule());
        var landed=built.delivered(pr42).landed(landing(42));
        assertEquals(landed,mapper.readValue(mapper.writeValueAsBytes(landed),WorkExecution.class));
    }
}
