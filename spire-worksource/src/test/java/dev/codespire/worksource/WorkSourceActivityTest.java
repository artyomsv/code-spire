package dev.codespire.worksource;

import org.junit.jupiter.api.Test;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

class WorkSourceActivityTest {
    final UUID gate=UUID.randomUUID();
    final String head="a".repeat(64);
    @Test void exactCommandBindsGenerationArtifactAndDecision() {
        var result=WorkSourceActivity.comment("TEST-comment","900123","/approve "+gate+" 7 "+head).answer();
        assertEquals(gate,result.gateId());assertEquals(7,result.generation());assertEquals(head,result.artifact());assertTrue(result.approve());
    }
    @Test void rejectionUsesTheSameBinding() {
        var result=WorkSourceActivity.comment("TEST-comment","900123","/reject "+gate+" 7 "+head).answer();assertFalse(result.approve());
    }
    @Test void ordinaryWordsAreNotCommands() {
        assertNull(WorkSourceActivity.comment("TEST-comment","900123","approve "+gate+" 7 "+head).answer());
    }
    @Test void missingArtifactIsAnExplicitDash() {
        assertNull(WorkSourceActivity.comment("TEST-comment","900123","/approve "+gate+" 7").answer());
        assertNull(WorkSourceActivity.comment("TEST-comment","900123","/approve "+gate+" 7 -").answer().artifact());
    }
    @Test void embeddedCommandDoesNotGrantAnAnswer() {
        assertNull(WorkSourceActivity.comment("TEST-comment","900123","TEST quoting /approve "+gate+" 7 "+head).answer());
    }
    @Test void malformedGenerationIsOrdinaryActivity() {
        assertNull(WorkSourceActivity.comment("TEST-comment","900123","/approve "+gate+" 99999999999999999999999 "+head).answer());
    }
}
