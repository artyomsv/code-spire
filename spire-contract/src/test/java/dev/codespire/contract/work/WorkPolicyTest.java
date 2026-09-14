package dev.codespire.contract.work;

import dev.codespire.worksource.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class WorkPolicyTest {
    final UUID id=UUID.randomUUID();
    WorkPolicy.Profile profile(int order,Map<WorkPolicy.Phase,String> modes){return new WorkPolicy.Profile(UUID.randomUUID(),"TEST-profile-"+order,1,order,modes);}
    CurrentLabel label(String name){return new CurrentLabel(name,"900123",LabelEvent.Origin.AUDIT_TRAIL,"TEST-event",CurrentLabel.Reason.ATTRIBUTED);}
    @Test void omittedPhaseIsOff(){assertEquals("off",profile(0,Map.of()).modes().get(WorkPolicy.Phase.INTAKE));}
    @Test void invalidOrdinaryModeIsRejected(){assertThrows(IllegalArgumentException.class,()->profile(0,Map.of(WorkPolicy.Phase.BUILD,"pr")));}
    @Test void invalidDeliveryModeIsRejected(){assertThrows(IllegalArgumentException.class,()->profile(0,Map.of(WorkPolicy.Phase.DELIVER,"auto")));}
    @Test void invalidLandModeIsRejected(){assertThrows(IllegalArgumentException.class,()->profile(0,Map.of(WorkPolicy.Phase.LAND,"auto")));}
    @Test void nullModeIsRejected(){Map<WorkPolicy.Phase,String> modes=new HashMap<>();modes.put(WorkPolicy.Phase.INTAKE,null);assertThrows(IllegalArgumentException.class,()->profile(0,modes));}
    @Test void blankNameIsRejected(){assertThrows(IllegalArgumentException.class,()->new WorkPolicy.Profile(id," ",1,0,Map.of()));}
    @Test void zeroVersionIsRejected(){assertThrows(IllegalArgumentException.class,()->new WorkPolicy.Profile(id,"TEST-name",0,0,Map.of()));}
    @Test void negativePrecedenceIsRejected(){assertThrows(IllegalArgumentException.class,()->new WorkPolicy.Profile(id,"TEST-name",1,-1,Map.of()));}
    @Test void anUnmappedLabelHasNoPolicyAuthority(){var ceiling=profile(0,Map.of(WorkPolicy.Phase.INTAKE,"auto"));assertNull(WorkPolicy.select(List.of(label("TEST-unmapped")),Set.of("900123"),Map.of(),ceiling,null).selected());}
    @Test void missingCeilingSelectsNothing(){var mapped=profile(0,Map.of(WorkPolicy.Phase.INTAKE,"auto"));var selected=WorkPolicy.select(List.of(label("TEST-label")),Set.of("900123"),Map.of("TEST-label",mapped),null,null);assertNull(selected.selected());assertEquals("ceiling_missing",selected.reason());}
    @Test void incomparableVectorsMeetEveryEligibleLabel(){
        var selected=profile(1,Map.of(WorkPolicy.Phase.INTAKE,"auto",WorkPolicy.Phase.PLAN,"auto",WorkPolicy.Phase.BUILD,"off"));
        var other=profile(2,Map.of(WorkPolicy.Phase.INTAKE,"auto",WorkPolicy.Phase.PLAN,"approve",WorkPolicy.Phase.BUILD,"auto"));
        var ceiling=profile(3,Map.of(WorkPolicy.Phase.INTAKE,"auto",WorkPolicy.Phase.PLAN,"auto",WorkPolicy.Phase.BUILD,"auto"));
        var policy=WorkPolicy.select(List.of(label("TEST-higher"),label("TEST-lower")),Set.of("900123"),Map.of("TEST-lower",selected,"TEST-higher",other),ceiling,null);
        assertEquals(selected,policy.selected());assertEquals("approve",policy.effective().get(WorkPolicy.Phase.PLAN));
        assertEquals("off",policy.effective().get(WorkPolicy.Phase.BUILD));assertEquals("policy_clamped",policy.reason());
    }
    @Test void pinnedVersionCannotBeWidened(){
        var mapped=profile(0,Map.of(WorkPolicy.Phase.INTAKE,"auto",WorkPolicy.Phase.BUILD,"auto"));
        var pinned=profile(1,Map.of(WorkPolicy.Phase.INTAKE,"auto",WorkPolicy.Phase.BUILD,"approve"));
        assertEquals("approve",WorkPolicy.select(List.of(label("TEST-label")),Set.of("900123"),Map.of("TEST-label",mapped),mapped,pinned.modes()).effective().get(WorkPolicy.Phase.BUILD));
    }
    @Test void ceilingReallyBoundsTheVector(){
        var mapped=profile(0,Map.of(WorkPolicy.Phase.INTAKE,"auto",WorkPolicy.Phase.BUILD,"auto"));
        var ceiling=profile(1,Map.of(WorkPolicy.Phase.INTAKE,"auto",WorkPolicy.Phase.BUILD,"off"));
        assertEquals("off",WorkPolicy.select(List.of(label("TEST-label")),Set.of("900123"),Map.of("TEST-label",mapped),ceiling,null).effective().get(WorkPolicy.Phase.BUILD));
    }
    @Test void anAdmissionCompositeCannotWidenWhenOneLabelDisappears(){
        var selected=profile(1,Map.of(WorkPolicy.Phase.INTAKE,"auto",WorkPolicy.Phase.PLAN,"auto",WorkPolicy.Phase.BUILD,"off"));
        var other=profile(2,Map.of(WorkPolicy.Phase.INTAKE,"auto",WorkPolicy.Phase.PLAN,"approve",WorkPolicy.Phase.BUILD,"auto"));
        var ceiling=profile(3,Map.of(WorkPolicy.Phase.INTAKE,"auto",WorkPolicy.Phase.PLAN,"auto",WorkPolicy.Phase.BUILD,"auto"));
        var admission=WorkPolicy.select(List.of(label("TEST-lower"),label("TEST-higher")),Set.of("900123"),Map.of("TEST-lower",selected,"TEST-higher",other),ceiling,null);
        var later=WorkPolicy.select(List.of(label("TEST-lower")),Set.of("900123"),Map.of("TEST-lower",selected),ceiling,admission.effective());
        assertEquals("approve",later.effective().get(WorkPolicy.Phase.PLAN));assertEquals("off",later.effective().get(WorkPolicy.Phase.BUILD));
        assertEquals(2,admission.applied().size());assertEquals("900123",admission.applied().getFirst().actorId());assertEquals(ceiling,later.ceiling());
    }
}
