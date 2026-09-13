package dev.codespire.orchestrator.work;

import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@io.quarkus.test.security.TestSecurity(user="TEST-operator",roles="spire-admin")
class WorkItemEventRoutingTest extends WorkFixture {
    @Test void neverWritesAReviewRow() throws Exception {
        long reviews=count("SELECT count(*) FROM review_status WHERE repository_id=?",repository);
        assertEquals(itemId,intake.accept(signed("900123")));
        assertEquals(reviews,count("SELECT count(*) FROM review_status WHERE repository_id=?",repository));
        assertEquals(1,count("SELECT count(*) FROM event_log WHERE stream_id=? AND event_type='WorkItemEvent'",itemId));
        assertEquals("cs.work-events",ConfigProvider.getConfig().getValue("mp.messaging.outgoing.work-events-out.topic",String.class));
        assertEquals("cs.work-integration",ConfigProvider.getConfig().getValue("mp.messaging.incoming.work-integration-in.topic",String.class));
        assertThrows(IllegalArgumentException.class,()->dev.codespire.orchestrator.eventstore.EventTypes.domainType("WorkItemEvent"));
    }
}
