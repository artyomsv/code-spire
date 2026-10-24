package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.RepositoryDelivery;
import dev.codespire.contract.event.RepositoryEventKind;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkLanding;
import dev.codespire.orchestrator.repository.RepositoryIngressConsumer;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * A pull request the factory delivered is merged or closed by hand on a repository with no FACTORY webhook (live land
 * acceptance, issue #64 on spire-test): the REVIEWER webhook reports the close, or no webhook arrives at all. The
 * reviewer's delivery is routed to the land observation, and a periodic read of the pull request converges an item
 * that heard nothing. Routing, the aggregate, the effect row and the sweep are production; GitHub is the fixture's
 * WireMock.
 */
@QuarkusTest
@TestSecurity(user="TEST-land-admin",roles="spire-admin")
class WorkLandUnwatchedIT extends WorkLandFixture {
    static final String PERSON="900123";
    @Inject RepositoryIngressConsumer ingress;
    @Inject WorkPullRequestWatch watch;

    /** What the REVIEWER webhook delivers when a pull request closes: no sender, only the close reason. */
    void reviewerReports(String id,IntegrationEvent.CloseReason reason,String delivery) {
        WorkItemEvent item=store.load(id);
        var closed=new IntegrationEvent.PullRequestClosed(sources.get(source).orElseThrow().repository(),item.progress().execution().pullRequest().number(),reason);
        ingress.accept(new RepositoryDelivery(repository,null,0,"github",forge.baseUrl(),RepositoryEventKind.REVIEWER,delivery,closed));
    }
    long postLandComments(String id) throws Exception {
        return count("SELECT count(*) FROM work_tracker_outbox WHERE work_item_id=? AND effect_class='post_land'",id);
    }

    // --- (a) the reviewer's close is the land observation ---
    @Test void aHandMergeTheReviewerReportsLandsTheItem() throws Exception {
        String id=atLandGate();mergedBy(PERSON);branchAt(HEAD,204);
        reviewerReports(id,IntegrationEvent.CloseReason.MERGED,"TEST-reviewer-merge");
        landed(id);assertEquals(1,landings(id));
        WorkLanding landing=store.load(id).progress().execution().landing();
        // The REVIEWER delivery names no merger; the pull request read does.
        assertEquals(PERSON,landing.mergedBy());assertEquals(MERGED,landing.mergeCommit());assertEquals("unknown",landing.method());
        assertEquals(1,postLandComments(id));
        assertEquals("cleaning",effect(id,"state"));
        land.drain();
        assertEquals(1,deletes());assertEquals(0,merges());assertEquals("done",effect(id,"state"));
    }
    @Test void anUnmergedCloseTheReviewerReportsStopsTheItem() throws Exception {
        String id=atLandGate();branchAt(HEAD,204);
        reviewerReports(id,IntegrationEvent.CloseReason.DECLINED,"TEST-reviewer-close");
        WorkItemEvent item=store.load(id);
        assertEquals("stopped",item.workflowStatus());assertEquals("pr_closed_by_human",item.reason());
        land.drain();
        assertEquals(0,deletes());assertEquals(0,landings(id));assertEquals(0,postLandComments(id));
    }
    @Test void theSameReviewerDeliveryTwiceLandsOnce() throws Exception {
        String id=atLandGate();mergedBy(PERSON);branchAt(HEAD,204);
        reviewerReports(id,IntegrationEvent.CloseReason.MERGED,"TEST-reviewer-merge-twice");
        reviewerReports(id,IntegrationEvent.CloseReason.MERGED,"TEST-reviewer-merge-twice");
        landed(id);assertEquals(1,landings(id));assertEquals(1,postLandComments(id));
    }
    /** The factory's own merge belongs to its land effect, which records it as the factory's; the reviewer's close does not. */
    @Test void theFactorysOwnMergeTheReviewerReportsIsLeftToTheEffect() throws Exception {
        factoryIdentity(FACTORY_ID);
        String id=approved();openAt(HEAD,"clean",null);merge(aResponse().withStatus(502));branchAt(HEAD,204);
        land.drain();assertEquals("uncertain",effect(id,"state"));
        mergedBy(FACTORY_ID);
        reviewerReports(id,IntegrationEvent.CloseReason.MERGED,"TEST-reviewer-factory-merge");
        assertEquals("active",store.load(id).workflowStatus());assertEquals(0,landings(id));
        land.drain();
        landed(id);assertEquals(1,landings(id));
        assertEquals(WorkLanding.FACTORY,store.load(id).progress().execution().landing().mergedBy());
    }
    /**
     * With no land effect to defer to, the machine rule alone keeps a merge by the factory account from being recorded
     * as a person's: the REVIEWER delivery names no sender, so the merger read from the pull request decides.
     */
    @Test void aMergeByTheFactoryAccountTheReviewerReportsIsNotRecordedAsAHandMerge() throws Exception {
        factoryIdentity(FACTORY_ID);
        String id=atLandGate();mergedBy(FACTORY_ID);
        reviewerReports(id,IntegrationEvent.CloseReason.MERGED,"TEST-reviewer-machine-merge");
        assertEquals(0,landings(id));assertEquals("waiting_approval",store.load(id).workflowStatus());
        assertEquals(0L,count("SELECT count(*) FROM work_land_observation WHERE work_item_id=?",id),"nothing is kept to be recorded later");
    }
    /** A read that fails is kept for the land sweep, as the factory webhook's merge is, and never dropped. */
    @Test void aReviewerReportedMergeWhoseReadFailsIsReadAgain() throws Exception {
        String id=atLandGate();java.time.Instant start=java.time.Instant.now();clockAt(start);branchAt(HEAD,204);
        forge.stubFor(get(urlPathEqualTo(pulls())).willReturn(aResponse().withStatus(502)));
        reviewerReports(id,IntegrationEvent.CloseReason.MERGED,"TEST-reviewer-merge-unread");
        assertEquals("waiting_approval",store.load(id).workflowStatus());
        now.set(start.plusSeconds(61));mergedBy(PERSON);
        land.drain();
        landed(id);assertEquals(PERSON,store.load(id).progress().execution().landing().mergedBy());
    }

    // --- (b) no webhook at all: the periodic read converges ---
    @Test void aHandMergeNoWebhookReportedLandsOnThePeriodicRead() throws Exception {
        String id=atLandGate();mergedBy(PERSON);branchAt(HEAD,204);
        watch.check();
        landed(id);assertEquals(1,landings(id));
        assertEquals(PERSON,store.load(id).progress().execution().landing().mergedBy());
        assertEquals(1,postLandComments(id));
        land.drain();
        assertEquals(1,deletes());assertEquals("done",effect(id,"state"));
        watch.check();
        assertEquals(1,landings(id));
    }
    @Test void aHandMergeInReviewLandsOnThePeriodicRead() throws Exception {
        String id=delivered(61);mergedBy(PERSON);branchAt(HEAD,204);
        watch.check();
        landed(id);assertEquals(1,landings(id));
    }
    @Test void anUnmergedCloseNoWebhookReportedStopsOnThePeriodicRead() throws Exception {
        String id=atLandGate();branchAt(HEAD,204);
        forge.stubFor(get(urlPathEqualTo(pulls())).willReturn(okJson(pullJson("closed",false,HEAD,"unknown",null))));
        watch.check();
        WorkItemEvent item=store.load(id);
        assertEquals("stopped",item.workflowStatus());assertEquals("pr_closed_by_human",item.reason());
        assertEquals(0,deletes());assertEquals(0,landings(id));
    }
    @Test void anOpenPullRequestIsLeftAsItIs() throws Exception {
        String id=atLandGate();openAt(HEAD,"clean",null);
        long revision=store.history(id).size();
        watch.check();
        assertEquals(revision,store.history(id).size());assertEquals("waiting_approval",store.load(id).workflowStatus());
    }
    /** An approved land has its own effect, which reads the pull request itself; the periodic read leaves it alone. */
    @Test void anItemWithALiveLandEffectIsNotRead() throws Exception {
        String id=approved();assertEquals("pending",effect(id,"state"));
        mergedBy(PERSON);
        int reads=pullReads();
        watch.check();
        assertEquals(reads,pullReads());assertEquals(0,landings(id));assertEquals("pending",effect(id,"state"));
    }
    /** A merge the factory account made is the land effect's to record; the periodic read never records it as a person's. */
    @Test void aMergeByTheFactoryAccountIsNotRecordedAsAHandMerge() throws Exception {
        factoryIdentity(FACTORY_ID);
        String id=atLandGate();mergedBy(FACTORY_ID);
        watch.check();
        assertEquals(0,landings(id));assertEquals("waiting_approval",store.load(id).workflowStatus());
    }
    @Test void anUnreadablePullRequestIsReadAgainOnTheNextCheck() throws Exception {
        String id=atLandGate();branchAt(HEAD,204);
        forge.stubFor(get(urlPathEqualTo(pulls())).willReturn(aResponse().withStatus(502)));
        watch.check();
        assertEquals("waiting_approval",store.load(id).workflowStatus());
        mergedBy(PERSON);
        watch.check();
        landed(id);
    }
}
