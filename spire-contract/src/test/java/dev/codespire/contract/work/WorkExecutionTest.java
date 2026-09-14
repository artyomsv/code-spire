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
    @Test void aVerificationWitherRequiresItsAttempt(){assertThrows(NullPointerException.class,()->built.verified(null));}
    @Test void aDeliveryWitherRequiresItsPullRequest(){assertThrows(NullPointerException.class,()->built.delivered(null));}
    @Test void aReviewWitherRequiresItsReview(){assertThrows(NullPointerException.class,()->built.reviewed(null));}
    @Test void phaseProofsPreserveTheBuildAndEarlierEvidence(){
        UUID verification=UUID.randomUUID();var pr=new PullRequestRef(1,"https://forge.example.test/TEST/1",true);
        assertEquals(new WorkExecution("TEST-run",binding,built.head(),verification,pr,"TEST-review"),built.verified(verification).delivered(pr).reviewed("TEST-review"));
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
    @Test void executionSurvivesAStoredProgressRoundTrip() throws Exception {
        var mapper=new ObjectMapper().registerModule(new JavaTimeModule());
        var progress=WorkProgress.empty().withExecution(built.verified(UUID.randomUUID()));
        assertEquals(progress,mapper.readValue(mapper.writeValueAsBytes(progress),WorkProgress.class));
        assertNull(mapper.readValue("{}",WorkProgress.class).execution());
    }
}
