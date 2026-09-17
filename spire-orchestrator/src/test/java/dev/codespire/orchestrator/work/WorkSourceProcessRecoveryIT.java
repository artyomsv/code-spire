package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.codespire.contract.event.WorkItemIds;
import dev.codespire.worksource.WorkIssueRef;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

/** Actual packaged JVM death; each child uses only this test's database/broker and ephemeral port. */
@QuarkusTest
class WorkSourceProcessRecoveryIT extends WorkFixture {
    @TempDir Path temporary;
    final List<Process> children=new ArrayList<>();
    final Map<Process,Path> logs=new HashMap<>();
    final List<String> extraItems=new ArrayList<>();
    String listing(int page) { return "/repos/"+scope+"/issues?state=open&sort=created&direction=asc&per_page=100&page="+page; }
    ObjectNode otherTicket() { return ticket().put("id",50002).put("number",43).put("html_url",forge.baseUrl()+"/"+scope+"/issues/43"); }
    String otherId() {
        var registered=sources.get(source).orElseThrow();
        String id=WorkItemIds.of(registered.scm(),registered.forgeOrigin(),registered.repository(),new WorkIssueRef(issue.ref().type(),issue.ref().origin(),"10001","50002"));
        if(!extraItems.contains(id))extraItems.add(id);return id;
    }
    void otherReads(boolean delayed) {
        String other="/repos/"+scope+"/issues/43";
        forge.stubFor(get(urlEqualTo(other)).willReturn(okJson(otherTicket().toString()).withFixedDelay(delayed?30000:0)));
        ObjectNode event=mapper.createObjectNode().put("id",102).put("event","labeled").put("created_at","2026-09-13T12:00:00Z");
        event.putObject("label").put("name",LABEL);event.putObject("actor").put("id",900123);
        forge.stubFor(get(urlEqualTo(other+"/timeline?per_page=100&page=1")).willReturn(okJson(mapper.createArrayNode().add(event).toString())));
    }
    @Test void restartResumesAfterCommittedCursor() throws Exception {
        String secondId=otherId();otherReads(false);
        forge.stubFor(get(urlEqualTo(listing(1))).willReturn(okJson(mapper.createArrayNode().add(ticket()).toString())
                .withHeader("Link","<"+forge.baseUrl()+listing(2)+">; rel=\"next\"")));
        forge.stubFor(get(urlEqualTo(listing(2))).willReturn(okJson(mapper.createArrayNode().add(otherTicket()).toString()).withFixedDelay(30000)));
        Process first=startScanner("TEST-between-pages-first");
        await().atMost(Duration.ofSeconds(45)).untilAsserted(()-> {
            assertAlive(first);assertEquals("2",sources.get(source).orElseThrow().cursor());
            assertFalse(forge.findAll(getRequestedFor(urlEqualTo(listing(2)))).isEmpty());
        });
        kill(first);assertEquals(1,store.history(itemId).size());assertNull(store.load(secondId));
        forge.resetRequests();
        // Refetching page one now fails: restart must load the committed cursor from storage.
        forge.stubFor(get(urlEqualTo(listing(1))).willReturn(aResponse().withStatus(503)));
        forge.stubFor(get(urlEqualTo(listing(2))).willReturn(okJson(mapper.createArrayNode().add(otherTicket()).toString())));
        Process second=startScanner("TEST-between-pages-second");
        await().atMost(Duration.ofSeconds(45)).untilAsserted(()-> {assertAlive(second);assertNotNull(store.load(secondId));assertNull(sources.get(source).orElseThrow().cursor());});
        kill(second);assertTwoSingleAdmissions(secondId);
        forge.verify(0,getRequestedFor(urlEqualTo(listing(1))));
    }
    @Test void restartResumesInsideAStagedPageWithoutRefetchingIt() throws Exception {
        String secondId=otherId();otherReads(true);
        forge.stubFor(get(urlEqualTo(listing(1))).willReturn(okJson(mapper.createArrayNode().add(ticket()).add(otherTicket()).toString())));
        Process first=startScanner("TEST-mid-page-first");
        await().atMost(Duration.ofSeconds(45)).untilAsserted(()-> {
            assertAlive(first);assertNotNull(store.load(itemId));
            assertFalse(forge.findAll(getRequestedFor(urlEqualTo("/repos/"+scope+"/issues/43"))).isEmpty());
            assertEquals(1,count("SELECT count(*) FROM work_scan_candidate WHERE source_id=?",source));
        });
        kill(first);assertNull(store.load(secondId));assertEquals(1,store.history(itemId).size());
        forge.resetRequests();otherReads(false);
        forge.stubFor(get(urlEqualTo(listing(1))).willReturn(aResponse().withStatus(503)));
        Process second=startScanner("TEST-mid-page-second");
        await().atMost(Duration.ofSeconds(45)).untilAsserted(()-> {
            assertAlive(second);assertNotNull(store.load(secondId));assertEquals(0,count("SELECT count(*) FROM work_scan_candidate WHERE source_id=?",source));
        });
        kill(second);assertTwoSingleAdmissions(secondId);
        forge.verify(0,getRequestedFor(urlEqualTo(listing(1))));
        forge.verify(0,getRequestedFor(urlEqualTo(path)));
    }
    void assertTwoSingleAdmissions(String secondId) throws Exception {
        assertEquals(2,count("SELECT count(*) FROM work_item WHERE source_id=?",source));
        assertEquals(1,store.history(itemId).size());assertEquals(1,store.history(secondId).size());
        assertEquals(0,count("SELECT count(*) FROM factory_run WHERE work_item_id=?",itemId));
        assertEquals(0,count("SELECT count(*) FROM factory_run WHERE work_item_id=?",secondId));
    }
    Process startScanner(String name) throws Exception {
        Process child=WorkProcessHarness.start(temporary,name,Map.of());
        children.add(child);logs.put(child,temporary.resolve(name+".log"));return child;
    }
    void assertAlive(Process child) { assertTrue(child.isAlive(),"The isolated scanner exited before its checkpoint; inspect the TEST process log in "+temporary); }
    void kill(Process child) throws Exception { child.destroyForcibly();assertTrue(child.waitFor(10,TimeUnit.SECONDS),"Scanner process did not stop"); }
    @AfterEach void stopChildrenAndCleanOtherItems() throws Exception {
        for(Process child:children)if(child.isAlive())kill(child);
        Path reports=Path.of(System.getProperty("spire.test.packaged-app")).getParent().getParent().resolve("reports/scanner-recovery");
        Files.createDirectories(reports);
        for(Path log:logs.values())Files.copy(log,reports.resolve(log.getFileName()),StandardCopyOption.REPLACE_EXISTING);
        for(String id:extraItems) {
            execute("DELETE FROM work_tracker_outbox WHERE work_item_id=?",id);
            execute("DELETE FROM work_item_gate WHERE work_item_id=?",id);execute("DELETE FROM work_item_outbox WHERE work_item_id=?",id);
            execute("DELETE FROM work_item_preparation_attempt WHERE work_item_id=?",id);
            execute("DELETE FROM work_item_artifact WHERE work_item_id=?",id);
            execute("DELETE FROM work_item_delivery WHERE work_item_id=?",id);execute("DELETE FROM work_item WHERE id=?",id);execute("DELETE FROM event_log WHERE stream_id=?",id);
        }
    }
}
