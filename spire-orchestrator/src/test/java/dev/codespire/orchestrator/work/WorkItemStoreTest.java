package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.worksource.LabelEvent;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.sql.Connection;
import java.sql.Statement;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@io.quarkus.test.security.TestSecurity(user="TEST-operator",roles="spire-admin")
class WorkItemStoreTest extends WorkFixture {
    @jakarta.inject.Inject dev.codespire.contract.port.EventStore durableEvents;
    @Test void restartRehydratesOnlyWorkflowMilestones() throws Exception {
        intake.accept(signed("900123"));
        WorkItemStore restarted=new WorkItemStore();restarted.events=durableEvents;
        WorkItemEvent rehydrated=restarted.load(itemId);
        assertNotNull(rehydrated);
        assertEquals(store.load(itemId),rehydrated);
        assertEquals("specification_required",rehydrated.reason());
        String json=mapper.writeValueAsString(rehydrated);
        assertFalse(json.contains("TEST-remote-title-secret"));assertFalse(json.contains("TEST-remote-body-secret"));
        assertFalse(json.contains("trackerStatus"));
        assertEquals("TEST-remote-title-secret",resource.tracker(itemId).title(),"content comes from a distinct live fetch");
    }

    @Test void rollbackLeavesNoGateEventOrOutboxEffect() throws Exception {
        WorkSourceRegistry.Source selected=sources.get(source).orElseThrow();
        WorkEvidence evidence=WorkEvidence.collect(()->sources.client(selected),issue,null);
        assertNull(evidence.failure());assertEquals(LabelEvent.Origin.AUDIT_TRAIL,evidence.labels().getFirst().origin());
        try(Connection c=dataSource.getConnection();Statement statement=c.createStatement()) {
            statement.execute("CREATE FUNCTION TEST_fail_work_projection() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'TEST projection failure after event append'; END; $$");
            statement.execute("CREATE TRIGGER TEST_fail_work_projection BEFORE INSERT ON work_item FOR EACH ROW EXECUTE FUNCTION TEST_fail_work_projection()");
        }
        try {
            assertThrows(RuntimeException.class,()->store.reconcile(selected,1,evidence,"TEST-rollback-delivery"));
            assertEquals(0,count("SELECT count(*) FROM event_log WHERE stream_id=?",itemId),"append must roll back with the failed projection");
            assertEquals(0,count("SELECT count(*) FROM work_item WHERE id=?",itemId));
            assertEquals(0,count("SELECT count(*) FROM work_item_delivery WHERE work_item_id=?",itemId));
            assertEquals(0,count("SELECT count(*) FROM work_item_gate WHERE work_item_id=?",itemId));
            assertEquals(0,count("SELECT count(*) FROM work_item_outbox WHERE work_item_id=?",itemId));
        } finally {
            try(Connection c=dataSource.getConnection();Statement statement=c.createStatement()) {
                statement.execute("DROP TRIGGER TEST_fail_work_projection ON work_item");statement.execute("DROP FUNCTION TEST_fail_work_projection()");
            }
        }
        assertEquals(itemId,store.reconcile(selected,1,evidence,"TEST-rollback-delivery"));
        assertEquals(1,store.history(itemId).size());
        assertEquals(1,count("SELECT count(*) FROM work_item_outbox WHERE work_item_id=?",itemId));
    }

    @Test void anAccountRotationAfterEvidenceRefusesTheCommit() throws Exception {
        WorkSourceRegistry.Source selected=sources.get(source).orElseThrow();
        WorkEvidence evidence=WorkEvidence.collect(()->sources.client(selected),issue,null);
        providers.update(account,new dev.codespire.orchestrator.provider.ProviderInput("TEST-rotated", "github",forge.baseUrl(),"bearer",null,
                "TEST-new-token","TEST-bot",true,List.of(),"TEST-bot",null,"FACTORY"));
        assertNull(store.reconcile(selected,1,evidence,"TEST-stale-account"));
        assertEquals(0,store.history(itemId).size());assertEquals("source_changed_during_read",sources.get(source).orElseThrow().health());
    }

    @Test void aPolicyEditAfterEvidenceRefusesTheCommit() throws Exception {
        WorkSourceRegistry.Source selected=sources.get(source).orElseThrow();
        WorkEvidence evidence=WorkEvidence.collect(()->sources.client(selected),issue,null);
        WorkPolicyRegistry.Pin pin=new WorkPolicyRegistry.Pin(profile,1);
        policies.save(repository,new WorkPolicyRegistry.Input(1,pin,java.util.Map.of(LABEL,pin)));
        assertNull(store.reconcile(selected,1,evidence,"TEST-stale-policy"));
        assertEquals(0,store.history(itemId).size());assertEquals("policy_changed_during_read",sources.get(source).orElseThrow().health());
    }
}
