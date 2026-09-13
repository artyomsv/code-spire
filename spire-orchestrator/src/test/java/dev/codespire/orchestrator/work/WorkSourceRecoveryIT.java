package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.worksource.LabelEvent;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class WorkSourceRecoveryIT extends WorkFixture {
    void candidates() {
        forge.stubFor(get(urlEqualTo("/repos/" + scope + "/issues?state=open&sort=created&direction=asc&per_page=100&page=1"))
                .willReturn(okJson(mapper.createArrayNode().add(ticket()).toString())));
    }

    @Test void backfillWithoutAuditRemainsUnattributed() throws Exception {
        candidates();
        forge.stubFor(get(urlEqualTo(path + "/timeline?per_page=100&page=1")).willReturn(aResponse().withStatus(403)));
        assertTrue(scanner.scan(source));
        WorkItemEvent item = store.load(itemId);
        assertNotNull(item);
        assertNull(item.policy().selected());
        assertEquals(LabelEvent.Origin.UNATTRIBUTED, item.policy().ignored().getFirst().origin());
        assertEquals("actor_id_missing", item.policy().ignored().getFirst().reason());
        assertEquals(0, count("SELECT count(*) FROM factory_run WHERE work_item_id=?", itemId));
        assertEquals(0, count("SELECT count(*) FROM work_item_gate WHERE work_item_id=?", itemId));
    }

    @Test void removeThenReaddCannotReuseAnOldAllowedActor() throws Exception {
        intake.accept(signed("900123"));
        assertEquals(profile, store.load(itemId).policy().selected().id());
        candidates();
        forge.stubFor(get(urlEqualTo(path + "/timeline?per_page=100&page=1")).willReturn(okJson(mapper.createArrayNode()
                .add(event(101, "labeled", "900123", "2026-09-13T12:00:00Z"))
                .add(event(102, "unlabeled", "900123", "2026-09-13T12:01:00Z"))
                .add(event(103, "labeled", null, "2026-09-13T12:02:00Z")).toString())));
        assertTrue(scanner.scan(source));
        WorkItemEvent item = store.load(itemId);
        assertNull(item.policy().selected());
        assertNull(item.policy().ignored().getFirst().actorId());
        assertEquals(LabelEvent.Origin.UNATTRIBUTED, item.policy().ignored().getFirst().origin());
        assertEquals("actor_id_missing", item.policy().ignored().getFirst().reason());
        assertEquals(2, store.history(itemId).size());
        assertEquals(0, count("SELECT count(*) FROM factory_run WHERE work_item_id=?", itemId));
    }

    ObjectNode event(int id, String action, String actor, String time) {
        ObjectNode event = mapper.createObjectNode().put("id", id).put("event", action).put("created_at", time);
        event.putObject("label").put("name", LABEL);
        if (actor == null) event.putNull("actor");
        else event.putObject("actor").put("id", Long.parseLong(actor));
        return event;
    }
}
