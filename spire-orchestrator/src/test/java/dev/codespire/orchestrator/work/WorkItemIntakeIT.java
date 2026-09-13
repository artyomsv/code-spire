package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.WorkSourceDelivery;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.worksource.LabelEvent;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@io.quarkus.test.security.TestSecurity(user="TEST-operator",roles="spire-admin")
class WorkItemIntakeIT extends WorkFixture {
    @Test void unlistedLabellerSelectsNoProfile() throws Exception {
        audit("900999");
        assertEquals(itemId,intake.accept(signed("900999")));
        WorkItemEvent item=store.load(itemId);
        assertNull(item.policy().selected());assertEquals("actor_not_allowed",item.policy().ignored().getFirst().reason());
        assertEquals(0,count("SELECT count(*) FROM factory_run WHERE work_item_id=?",itemId));
        assertEquals(0,count("SELECT count(*) FROM work_item_outbox WHERE work_item_id=? AND effect_type <> 'WORK_EVENT'",itemId));
    }
    @Test void unattributedCurrentLabelSelectsNoProfile() throws Exception {
        forge.stubFor(get(urlEqualTo(path+"/timeline?per_page=100&page=1")).willReturn(aResponse().withStatus(503)));
        assertEquals(itemId,intake.accept(signed("900123")));
        WorkItemEvent item=store.load(itemId);
        assertNull(item.policy().selected());
        assertEquals("900123",item.policy().ignored().getFirst().actorId(),"the hint must pass membership");
        assertEquals(LabelEvent.Origin.UNATTRIBUTED,item.policy().ignored().getFirst().origin());
        assertEquals("label_unattributed",item.policy().ignored().getFirst().reason());
        assertEquals(0,count("SELECT count(*) FROM factory_run WHERE work_item_id=?",itemId));
        assertEquals(0,count("SELECT count(*) FROM work_item_outbox WHERE work_item_id=? AND effect_type <> 'WORK_EVENT'",itemId));
    }
    @Test void allowedAttributedLabellerCanSelect() throws Exception {
        assertEquals(itemId,intake.accept(signed("900123")));
        WorkItemEvent item=store.load(itemId);
        assertEquals(profile,item.policy().selected().id());assertTrue(item.policy().ignored().isEmpty());
        assertEquals("awaiting_input",item.workflowStatus());assertEquals("specification_required",item.reason());
        assertEquals(1,count("SELECT count(*) FROM work_item_outbox WHERE work_item_id=?",itemId));
    }
    @Test void aGenuinelyMissingActorSelectsNoProfile() throws Exception {
        audit(null);assertEquals(itemId,intake.accept(signed(null)));
        WorkItemEvent item=store.load(itemId);
        assertNull(item.policy().selected());assertEquals("actor_id_missing",item.policy().ignored().getFirst().reason());
        assertEquals(0,count("SELECT count(*) FROM factory_run WHERE work_item_id=?",itemId));
    }
    @Test void signedLabelCreatesOneVisibleItemAcrossRedelivery() throws Exception {
        WorkSourceDelivery delivery=signed("900123");
        assertEquals(itemId,intake.accept(delivery));assertEquals(itemId,intake.accept(delivery));
        assertEquals(1,count("SELECT count(*) FROM work_item WHERE id=?",itemId));
        assertEquals(1,count("SELECT count(*) FROM work_item_delivery WHERE work_item_id=?",itemId));
        assertEquals(1,store.history(itemId).size());assertEquals(1,count("SELECT count(*) FROM work_item_outbox WHERE work_item_id=?",itemId));
        assertEquals(itemId,resource.get(itemId).id());
        assertTrue(resource.list(0,100).items().stream().anyMatch(item->item.id().equals(itemId)));
    }
    @Test void aRescanUsesTheSamePolicyAndDoesNotDuplicateTheItem() throws Exception {
        intake.accept(signed("900123"));
        forge.stubFor(get(urlEqualTo("/repos/"+scope+"/issues?state=open&sort=created&direction=asc&per_page=100&page=1"))
                .willReturn(okJson(mapper.createArrayNode().add(ticket()).toString())));
        assertTrue(scanner.scan(source));assertEquals(1,store.history(itemId).size());
        assertEquals(1,count("SELECT count(*) FROM work_item WHERE id=?",itemId));
        assertNull(sources.get(source).orElseThrow().cursor());
    }
    @Test void anAccountOutageCannotRetireOrDeleteTheWorkflow() throws Exception {
        intake.accept(signed("900123"));
        forge.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(403)));
        assertNull(intake.accept(signed("900123")));
        assertEquals("awaiting_input",resource.get(itemId).workflowStatus());
        assertEquals("tracker_unavailable",sources.get(source).orElseThrow().health());
        assertThrows(jakarta.ws.rs.ServiceUnavailableException.class,()->resource.tracker(itemId));
    }
}
