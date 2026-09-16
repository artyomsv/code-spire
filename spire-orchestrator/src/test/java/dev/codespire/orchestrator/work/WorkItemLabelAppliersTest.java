package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.WorkPolicy;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The item view names the people on its labels. The source's allowlist is authorization
 * configuration and the view reaches viewers on every list row, so nobody else may ride along.
 */
class WorkItemLabelAppliersTest {
    static final WorkSourceRegistry.Person APPLIER=new WorkSourceRegistry.Person("TEST-900123","TEST-person","TEST Person");
    static final WorkSourceRegistry.Person BYSTANDER=new WorkSourceRegistry.Person("TEST-900456","TEST-bystander","TEST Bystander");

    static WorkPolicy.AppliedLabel label(String actor) {
        return new WorkPolicy.AppliedLabel("TEST-label",actor,dev.codespire.worksource.LabelEvent.Origin.AUDIT_TRAIL,"TEST-event",UUID.randomUUID(),1);
    }

    @Test void onlyThePeopleOnTheLabelsAreNamed() {
        assertEquals(List.of(APPLIER),WorkItemResource.labelAppliers(List.of(APPLIER,BYSTANDER),List.of(label("TEST-900123"))));
    }

    @Test void anItemWithNoLabelsNamesNobody() {
        assertEquals(List.of(),WorkItemResource.labelAppliers(List.of(APPLIER,BYSTANDER),List.of()));
    }

    /** An applier who is no longer allowed has no observed handle to show; the id stays on the label. */
    @Test void anApplierMissingFromTheAllowlistIsNotInvented() {
        assertEquals(List.of(),WorkItemResource.labelAppliers(List.of(BYSTANDER),List.of(label("TEST-900123"))));
    }
}
