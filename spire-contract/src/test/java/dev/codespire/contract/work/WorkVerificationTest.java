package dev.codespire.contract.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class WorkVerificationTest {
    final UUID attempt=UUID.randomUUID();
    final String head="b".repeat(40);
    WorkVerification.CheckResult check(Integer exit){return new WorkVerification.CheckResult("TEST-check",exit,5,"TEST-tail");}

    @Test void passedNeedsEveryCheckToExitZeroAndNoReason(){
        assertTrue(new WorkVerification(attempt,head,WorkVerification.Outcome.PASSED,null,List.of(check(0),check(0))).passed());
        assertThrows(IllegalArgumentException.class,()->new WorkVerification(attempt,head,WorkVerification.Outcome.PASSED,null,List.of(check(1))));
        assertThrows(IllegalArgumentException.class,()->new WorkVerification(attempt,head,WorkVerification.Outcome.PASSED,null,List.of()));
        assertThrows(IllegalArgumentException.class,()->new WorkVerification(attempt,head,WorkVerification.Outcome.PASSED,"check_failed",List.of(check(0))));
    }
    @Test void aResultThatIsNotPassedNamesAKnownReason(){
        assertThrows(IllegalArgumentException.class,()->new WorkVerification(attempt,head,WorkVerification.Outcome.FAILED,null,List.of(check(1))));
        assertThrows(IllegalArgumentException.class,()->new WorkVerification(attempt,head,WorkVerification.Outcome.UNVERIFIED,"TEST-unknown",List.of()));
        assertThrows(IllegalArgumentException.class,()->new WorkVerification(attempt,head,WorkVerification.Outcome.FAILED,"tool_missing",List.of(check(127))));
        assertFalse(new WorkVerification(attempt,head,WorkVerification.Outcome.UNVERIFIED,"no_checks_declared",List.of()).passed());
    }
    @Test void aPartialHeadIsRefused(){
        assertThrows(IllegalArgumentException.class,()->new WorkVerification(attempt,"abc",WorkVerification.Outcome.UNVERIFIED,"timed_out",List.of()));
    }
    @Test void aTailOverTheCapIsRefused(){
        String big="x".repeat(WorkVerification.MAX_TAIL_CHARS+1);
        assertThrows(IllegalArgumentException.class,()->new WorkVerification.CheckResult("TEST-check",1,1,big));
    }
    @Test void survivesAJsonRoundTrip() throws Exception {
        var mapper=new ObjectMapper().findAndRegisterModules();
        var value=new WorkVerification(attempt,head,WorkVerification.Outcome.FAILED,"check_failed",List.of(check(0),check(2)));
        assertEquals(value,mapper.readValue(mapper.writeValueAsBytes(value),WorkVerification.class));
    }
}
