package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.work.*;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import java.sql.*;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A person merges or closes the pull request on GitHub (M4 land, spec §7.8, A2, A10). The webhook arrives as the
 * activity GitHubIngress makes of it; routing, the aggregate, the effect row and the sweep are production.
 */
@QuarkusTest
@TestSecurity(user="TEST-land-admin",roles="spire-admin")
class WorkLandWebhookIT extends WorkLandFixture {
    static final String PERSON="900123";
    @Inject WorkGateChannels channels;

    /** What GitHubIngress makes of a pull request closed on GitHub: the sender stays the actor, merged_by is the merger. */
    IntegrationEvent.RepositoryActivity closedOnGitHub(String id,String kind,String sender,String mergedBy) {
        WorkItemEvent item=store.load(id);
        return new IntegrationEvent.RepositoryActivity(sources.get(source).orElseThrow().repository(),kind,sender,"refs/heads/"+item.control().branch(),
                HEAD,item.progress().execution().pullRequest().number(),null,null,false).withMergedBy(mergedBy);
    }
    String receipt(String id,String delivery) throws SQLException {
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement(
                "SELECT outcome FROM work_activity_receipt WHERE work_item_id=? AND channel='scm' AND delivery_id=?")) {
            ps.setString(1,id);ps.setString(2,delivery);
            try(ResultSet rs=ps.executeQuery()){return rs.next()?rs.getString(1):null;}
        }
    }
    long postLandComments(String id) throws SQLException {
        return count("SELECT count(*) FROM work_tracker_outbox WHERE work_item_id=? AND effect_class='post_land'",id);
    }
    boolean takenOver(String id) {
        return store.history(id).stream().anyMatch(event->"HUMAN_TAKEOVER".equals(((WorkItemEvent)event.payload()).milestone()));
    }

    @Test void aHandMergeCompletesWithTheHumanAsMerger() throws Exception {
        String id=atLandGate();mergedBy(PERSON);branchAt(HEAD,204);
        channels.activity(repository,closedOnGitHub(id,"merged",PERSON,PERSON),"TEST-hand-merge");
        landed(id);assertFalse(takenOver(id));
        WorkLanding landing=store.load(id).progress().execution().landing();
        assertEquals(PERSON,landing.mergedBy());assertEquals(MERGED,landing.mergeCommit());
        assertEquals(1,postLandComments(id));
        // The branch is the factory's, so it is still deleted, by the sweep and at the merged head.
        assertEquals("cleaning",effect(id,"state"));assertEquals(PERSON,effect(id,"merged_by"));
        land.drain();
        assertEquals(1,deletes());assertEquals(0,merges());assertEquals("done",effect(id,"state"));
    }
    @Test void aHandMergeOfAnApprovedLandMovesItsEffectToCleaning() throws Exception {
        String id=approved();assertEquals("pending",effect(id,"state"));
        mergedBy(PERSON);branchAt(HEAD,204);
        channels.activity(repository,closedOnGitHub(id,"merged",PERSON,PERSON),"TEST-hand-merge-approved");
        landed(id);assertEquals("cleaning",effect(id,"state"));assertEquals(MERGED,effect(id,"merge_commit"));
        land.drain();
        assertEquals(0,merges());assertEquals(1,deletes());assertEquals(1,landings(id));
    }
    @Test void theFactorysOwnMergeWebhookIsLeftToTheEffect() throws Exception {
        factoryIdentity(FACTORY_ID);
        String id=approved();openAt(HEAD,"clean",null);merge(aResponse().withStatus(502));branchAt(HEAD,204);
        land.drain();assertEquals("uncertain",effect(id,"state"));
        // GitHub finished the factory's merge, and its webhook arrives before the effect read the merge back.
        mergedBy(FACTORY_ID);
        channels.activity(repository,closedOnGitHub(id,"merged",FACTORY_ID,FACTORY_ID),"TEST-factory-merge-hook");
        assertEquals("recorded_machine",receipt(id,"TEST-factory-merge-hook"));
        assertEquals("active",store.load(id).workflowStatus());assertEquals(0,landings(id));
        land.drain();
        assertEquals(1,landings(id));landed(id);
        assertEquals(WorkLanding.FACTORY,store.load(id).progress().execution().landing().mergedBy());
    }
    @Test void aMergeWebhookFromTheRecordedFactoryAfterRotationIsLeftToTheEffect() throws Exception {
        factoryIdentity(FACTORY_ID);String id=approved();
        assertEquals(FACTORY_ID,store.load(id).control().factoryActor());
        factoryIdentity("900322");mergedBy(FACTORY_ID);
        channels.activity(repository,closedOnGitHub(id,"merged",FACTORY_ID,FACTORY_ID),"TEST-recorded-factory-hook");
        assertEquals("recorded_machine",receipt(id,"TEST-recorded-factory-hook"));
        assertEquals("active",store.load(id).workflowStatus());assertEquals(0,landings(id));
    }
    @Test void aMergeWebhookFromTheCurrentFactoryIsLeftToTheEffect() throws Exception {
        String id=approved();factoryIdentity(FACTORY_ID);
        assertNotEquals(FACTORY_ID,store.load(id).control().factoryActor(),"the claim recorded the identity before the rotation");
        mergedBy(FACTORY_ID);
        channels.activity(repository,closedOnGitHub(id,"merged",FACTORY_ID,FACTORY_ID),"TEST-current-factory-hook");
        assertEquals("recorded_machine",receipt(id,"TEST-current-factory-hook"));
        assertEquals("active",store.load(id).workflowStatus());assertEquals(0,landings(id));
    }
    // The webhook loop skips a completed item, so the second observer is called directly.
    @Test void aSecondLandingForTheSamePullRequestIsANoOp() throws Exception {
        String id=approved();openAt(HEAD,"clean",null);merge(merged());branchAt(HEAD,204);
        land.drain();landed(id);
        mergedBy(PERSON);
        WorkItemTransitions.Outcome again=transitions.landedByHuman(id,"TEST-late-hook",store.load(id).progress().execution().pullRequest().number(),PERSON);
        assertEquals(200,again.status());assertEquals("landing_already_recorded",again.reason());
        assertEquals(1,landings(id));assertEquals(WorkLanding.FACTORY,store.load(id).progress().execution().landing().mergedBy());
    }
    @Test void aHandMergeWhileInReviewCompletes() throws Exception {
        String id=delivered(61);
        assertEquals("review",store.load(id).phase());assertEquals("active",store.load(id).workflowStatus());
        mergedBy(PERSON);branchAt(HEAD,204);
        channels.activity(repository,closedOnGitHub(id,"merged",PERSON,PERSON),"TEST-merge-in-review");
        landed(id);assertFalse(takenOver(id));
        assertEquals(PERSON,store.load(id).progress().execution().landing().mergedBy());
    }
    @Test void aHandMergeOfASuspendedItemCompletes() throws Exception {
        String id=atLandGate();control.suspend(id,"scm","TEST-human-push","900555",HEAD);
        assertEquals("suspended",store.load(id).workflowStatus());
        mergedBy(PERSON);branchAt(HEAD,204);
        channels.activity(repository,closedOnGitHub(id,"merged",PERSON,PERSON),"TEST-merge-after-takeover");
        landed(id);assertEquals(PERSON,store.load(id).progress().execution().landing().mergedBy());
    }
    @Test void aCloseStopsTheItemWithoutDeletingTheBranch() throws Exception {
        String id=atLandGate();branchAt(HEAD,204);
        channels.activity(repository,closedOnGitHub(id,"closed",PERSON,null),"TEST-hand-close");
        WorkItemEvent item=store.load(id);
        assertEquals("stopped",item.workflowStatus());assertEquals("pr_closed_by_human",item.reason());assertEquals("PULL_REQUEST_CLOSED",item.milestone());
        assertFalse(takenOver(id));
        land.drain();
        assertEquals(0,deletes());assertEquals(0,landings(id));assertEquals(0,postLandComments(id));
    }
    @Test void aCloseOfAnApprovedLandEndsItsEffect() throws Exception {
        String id=approved();assertEquals("pending",effect(id,"state"));
        openAt(HEAD,"clean",null);merge(merged());branchAt(HEAD,204);
        channels.activity(repository,closedOnGitHub(id,"closed",PERSON,null),"TEST-hand-close-approved");
        assertEquals("stopped",store.load(id).workflowStatus());assertEquals("closed",effect(id,"state"));
        land.drain();
        assertEquals(0,merges());assertEquals(0,deletes());
    }
}
