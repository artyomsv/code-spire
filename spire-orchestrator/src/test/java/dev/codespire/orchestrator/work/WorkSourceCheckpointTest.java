package dev.codespire.orchestrator.work;

import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;
import java.sql.*;
import java.time.Duration;
import java.util.concurrent.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
class WorkSourceCheckpointTest extends WorkFixture {
    String listing(){return "/repos/"+scope+"/issues?state=open&sort=created&direction=asc&per_page=100&page=1";}
    void page(int count) {
        var rows=mapper.createArrayNode();for(int i=0;i<count;i++)rows.add(ticket());
        forge.stubFor(get(urlEqualTo(listing())).willReturn(okJson(rows.toString())));
    }
    @Test void checkpointFailureRollsBackAdmissionAndEveryEffect() throws Exception {
        page(1);
        try(Connection c=dataSource.getConnection();Statement statement=c.createStatement()) {
            statement.execute("CREATE FUNCTION TEST_fail_work_checkpoint() RETURNS trigger LANGUAGE plpgsql AS $$ BEGIN RAISE EXCEPTION 'TEST checkpoint failure after admission'; END; $$");
            statement.execute("CREATE TRIGGER TEST_fail_work_checkpoint BEFORE DELETE ON work_scan_candidate FOR EACH ROW EXECUTE FUNCTION TEST_fail_work_checkpoint()");
        }
        try {
            assertThrows(RuntimeException.class,()->scanner.scan(source));
            assertEquals(0,count("SELECT count(*) FROM event_log WHERE stream_id=?",itemId),"admission must roll back with the checkpoint");
            assertEquals(0,count("SELECT count(*) FROM work_item WHERE id=?",itemId));
            assertEquals(0,count("SELECT count(*) FROM work_item_delivery WHERE work_item_id=?",itemId));
            assertEquals(0,count("SELECT count(*) FROM work_item_outbox WHERE work_item_id=?",itemId));
            assertEquals(0,count("SELECT count(*) FROM work_tracker_outbox WHERE work_item_id=?",itemId));
            assertEquals(1,count("SELECT count(*) FROM work_scan_candidate WHERE source_id=?",source));
            assertNull(sources.get(source).orElseThrow().cursor());
        } finally {
            try(Connection c=dataSource.getConnection();Statement statement=c.createStatement()) {
                statement.execute("DROP TRIGGER TEST_fail_work_checkpoint ON work_scan_candidate");statement.execute("DROP FUNCTION TEST_fail_work_checkpoint()");
            }
        }
        assertTrue(scanner.scan(source));assertEquals(1,store.history(itemId).size());
        assertEquals(0,count("SELECT count(*) FROM work_scan_candidate WHERE source_id=?",source));
    }
    @Test void unavailableTicketKeepsTheStagedCoordinate() throws Exception {
        page(1);forge.stubFor(get(urlEqualTo(path)).willReturn(aResponse().withStatus(403)));
        assertFalse(scanner.scan(source));assertEquals("scan_unavailable",sources.get(source).orElseThrow().health());
        assertNull(store.load(itemId));assertEquals(1,count("SELECT count(*) FROM work_scan_candidate WHERE source_id=?",source));
        forge.stubFor(get(urlEqualTo(path)).willReturn(okJson(ticket().toString())));
        forge.stubFor(get(urlEqualTo(listing())).willReturn(aResponse().withStatus(503)));
        assertTrue(scanner.scan(source));assertEquals(1,store.history(itemId).size());
    }
    @Test void oversizedCandidatePageCannotStageAnything() throws Exception {
        page(101);assertFalse(scanner.scan(source));assertEquals("scan_unavailable",sources.get(source).orElseThrow().health());
        assertEquals(0,count("SELECT count(*) FROM work_scan_candidate WHERE source_id=?",source));assertNull(store.load(itemId));
    }
    @Test void aSweepStopsAfterTenCommittedCoordinates() throws Exception {
        page(11);assertTrue(scanner.scan(source));
        assertEquals(1,count("SELECT count(*) FROM work_scan_candidate WHERE source_id=?",source));
        assertEquals(1,store.history(itemId).size());
        assertTrue(scanner.scan(source));assertEquals(0,count("SELECT count(*) FROM work_scan_candidate WHERE source_id=?",source));
    }
    @Test void concurrentReconciliationCommitsOneCheckpoint() throws Exception {
        page(1);forge.stubFor(get(urlEqualTo(path)).willReturn(okJson(ticket().toString()).withFixedDelay(2000)));
        FutureTask<Boolean> first=new FutureTask<>(()->scanner.scan(source));Thread.ofVirtual().start(first);
        await().atMost(Duration.ofSeconds(5)).until(()->!forge.findAll(getRequestedFor(urlEqualTo(path))).isEmpty());
        FutureTask<Boolean> second=new FutureTask<>(()->scanner.scan(source));Thread.ofVirtual().start(second);
        boolean a=assertDoesNotThrow(()->first.get(15,TimeUnit.SECONDS)),b=assertDoesNotThrow(()->second.get(15,TimeUnit.SECONDS));
        assertNotEquals(a,b,"Exactly one concurrent checkpoint commits");assertEquals(1,store.history(itemId).size());
        assertEquals(0,count("SELECT count(*) FROM work_scan_candidate WHERE source_id=?",source));
    }
}
