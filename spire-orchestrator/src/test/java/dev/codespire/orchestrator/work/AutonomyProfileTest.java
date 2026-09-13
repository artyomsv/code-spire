package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.*;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class AutonomyProfileTest extends WorkPolicyFixture {
    @Test void omittedPhaseIsOff() {
        var profile=new WorkPolicy.Profile(UUID.randomUUID(),"TEST-omitted",1,0,Map.of());assertEquals("off",profile.modes().get(WorkPolicy.Phase.PLAN));
    }
    @Test void incomparableVectorsMeetWithoutWideningEither() {
        var suggestModes=automatic();suggestModes.put(WorkPolicy.Phase.BUILD,"off");
        var assistedModes=automatic();assistedModes.put(WorkPolicy.Phase.PLAN,"approve");
        var suggest=profile("suggest",1_000_000_100,suggestModes);var assisted=profile("assisted",1_000_000_101,assistedModes);var ceiling=profile("ceiling",1_000_000_102,automatic());
        var labels=List.of(new dev.codespire.worksource.CurrentLabel("TEST-suggest","900123",dev.codespire.worksource.LabelEvent.Origin.AUDIT_TRAIL,"TEST-first",dev.codespire.worksource.CurrentLabel.Reason.ATTRIBUTED),
                new dev.codespire.worksource.CurrentLabel("TEST-assisted","900123",dev.codespire.worksource.LabelEvent.Origin.AUDIT_TRAIL,"TEST-second",dev.codespire.worksource.CurrentLabel.Reason.ATTRIBUTED));
        var selected=WorkPolicy.select(labels,Set.of("900123"),Map.of("TEST-suggest",suggest,"TEST-assisted",assisted),ceiling,null);
        assertEquals(suggest,selected.selected());assertEquals("approve",selected.effective().get(WorkPolicy.Phase.PLAN));assertEquals("off",selected.effective().get(WorkPolicy.Phase.BUILD));
    }
    @Test void requiresDistinctProfilePrecedence() {
        configureHigh();assertThrows(IllegalArgumentException.class,()->profile("duplicate-precedence",requested.precedence(),automatic()));
    }
    @Test void invalidProtectedGlobIsRejectedBeforeItCanPretendToProtectAPath() {
        UUID id=UUID.randomUUID();extraProfiles.add(id);
        assertThrows(IllegalArgumentException.class,()->policies.createVersion(new WorkPolicy.Profile(id,"TEST-invalid-glob-"+id,1,1_000_000_100,automatic(),
                new WorkPolicyLimits(60,1,1,60,100,1,Set.of("TEST-[unsupported]")))));
    }
}
