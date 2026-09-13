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
    @Test void anAccountOutageCannotRetireOrDeleteTheWorkflow() throws Exception {
        intake.accept(signed("900123"));
        forge.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(403)));
        assertNull(intake.accept(signed("900123")));
        assertEquals("awaiting_input",resource.get(itemId).workflowStatus());
        assertEquals("tracker_unavailable",sources.get(source).orElseThrow().health());
        assertThrows(jakarta.ws.rs.ServiceUnavailableException.class,()->resource.tracker(itemId));
    }
}
