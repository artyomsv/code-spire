package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.WorkSourceDelivery;
import dev.codespire.worksource.WorkSourceType;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@io.quarkus.test.security.TestSecurity(user="TEST-operator",roles="spire-admin")
class WorkItemIntakeIT extends WorkSourceParityCases {
    @Override WorkSourceType type() { return WorkSourceType.GITHUB; }
    @Test void signedLabelCreatesOneVisibleItemAcrossRedelivery() throws Exception {
        WorkSourceDelivery delivery=signed("900123");
        assertEquals(itemId,intake.accept(delivery));assertEquals(itemId,intake.accept(delivery));
        assertEquals(1,count("SELECT count(*) FROM work_item WHERE id=?",itemId));
        assertEquals(1,count("SELECT count(*) FROM work_item_delivery WHERE work_item_id=?",itemId));
        assertEquals(1,store.history(itemId).size());assertEquals(1,count("SELECT count(*) FROM work_item_outbox WHERE work_item_id=?",itemId));
        assertEquals(itemId,resource.get(itemId).id());
        assertTrue(resource.list(0,100,null).items().stream().anyMatch(item->item.id().equals(itemId)));
    }
    @Test void aRescanUsesTheSamePolicyAndDoesNotDuplicateTheItem() throws Exception {
        intake.accept(signed("900123"));
        forge.stubFor(get(urlEqualTo("/repos/"+scope+"/issues?state=open&sort=created&direction=asc&per_page=100&page=1"))
                .willReturn(okJson(mapper.createArrayNode().add(ticket()).toString())));
        assertTrue(scanner.scan(source));assertEquals(1,store.history(itemId).size());
        assertEquals(1,count("SELECT count(*) FROM work_item WHERE id=?",itemId));
        assertNull(sources.get(source).orElseThrow().cursor());
    }
    /**
     * A label stores the stable provider id, which is the only identity safe to keep. The screen has
     * to name a person, so the read carries the handles the tracker observed for this source's
     * allowed people — resolved per read, never written onto the label.
     */
    @Test void theItemCarriesTheObservedHandlesForTheIdsOnItsLabels() throws Exception {
        intake.accept(signed("900123"));
        var view=resource.get(itemId);
        assertTrue(view.appliedLabels().stream().anyMatch(label->"900123".equals(label.actorId())),"the label keeps the id");
        var person=view.people().stream().filter(row->"900123".equals(row.providerUserId())).findFirst().orElseThrow();
        assertEquals("TEST-person",person.handle());
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
