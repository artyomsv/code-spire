package dev.codespire.contract.work;

import dev.codespire.worksource.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkPolicyBoundsTest {
    @Test void gateLifetimeCannotOverflowTheStoredDeadline(){assertThrows(IllegalArgumentException.class,()->new WorkPolicyLimits(Long.MAX_VALUE,1,1,1,1,1,Set.of()));}
    WorkPolicyLimits limits(long calls,String path){return new WorkPolicyLimits(60,5,20,7200,2_000_000,calls,Set.of(path));}
    WorkPolicy.Profile profile(int order,long calls,String path){return new WorkPolicy.Profile(UUID.randomUUID(),"TEST-profile-"+order,1,order,Map.of(WorkPolicy.Phase.INTAKE,"auto"),limits(calls,path));}
    CurrentLabel label(String name){return new CurrentLabel(name,"900123",LabelEvent.Origin.AUDIT_TRAIL,"TEST-event",CurrentLabel.Reason.ATTRIBUTED);}
    @Test void everyEligibleLabelRestrictsNumericCaps(){
        var selected=profile(1,40,"TEST-selected/**");var other=profile(2,3,"TEST-other/**");var ceiling=profile(3,100,"TEST-ceiling/**");
        var result=WorkPolicy.select(List.of(label("TEST-first"),label("TEST-other")),Set.of("900123"),Map.of("TEST-first",selected,"TEST-other",other),ceiling,null,null);
        assertEquals(selected,result.selected());assertEquals(3,result.limits().maxCallsPerItem());assertEquals("policy_clamped",result.reason());
    }
    @Test void admittedCapsCannotBeWidenedByNewLabelsOrCeilings(){
        var selected=profile(1,40,"TEST-selected/**");
        var result=WorkPolicy.select(List.of(label("TEST-first")),Set.of("900123"),Map.of("TEST-first",selected),selected,null,limits(2,"TEST-admitted/**"));
        assertEquals(2,result.limits().maxCallsPerItem());assertEquals("policy_clamped",result.reason());
    }
    @Test void allAppliedProtectedPathsAccumulate(){
        var selected=profile(1,40,"TEST-selected/**");var other=profile(2,40,"TEST-other/**");var ceiling=profile(3,40,"TEST-ceiling/**");
        var result=WorkPolicy.select(List.of(label("TEST-first"),label("TEST-other")),Set.of("900123"),Map.of("TEST-first",selected,"TEST-other",other),ceiling,null,limits(40,"TEST-admitted/**"));
        assertEquals(Set.of("TEST-selected/**","TEST-other/**","TEST-ceiling/**","TEST-admitted/**"),result.limits().protectedPaths());
    }
    @Test void aNumericOnlyRestrictionMustSayItWasClamped(){
        var selected=profile(1,40,"TEST-common/**");var ceiling=profile(2,2,"TEST-common/**");
        var result=WorkPolicy.select(List.of(label("TEST-first")),Set.of("900123"),Map.of("TEST-first",selected),ceiling,null,null);
        assertEquals(selected.modes(),result.effective());assertEquals("policy_clamped",result.reason());
    }
}
