package dev.codespire.orchestrator.work;

import dev.codespire.worksource.*;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkEvidenceTest {
    final WorkIssueRef ref=new WorkIssueRef(WorkSourceType.GITHUB,"https://api.github.com","10001","50001");
    final WorkIssueLocation issue=new WorkIssueLocation(ref,"42",URI.create("https://github.com/TEST-work/TEST-repo/issues/42"));
    LabelEvent event(int number){return new LabelEvent("TEST-event-"+number,ref,"TEST-label",LabelEvent.Action.ADD,"900123",Instant.ofEpochSecond(number),null,LabelEvent.Origin.AUDIT_TRAIL);}
    WorkTicket ticket(WorkIssueLocation location,Set<String> labels){return new WorkTicket(location,"TEST-title","TEST-body","open",labels);}
    class Source implements WorkSource {
        int reads,pages;
        java.util.function.IntFunction<Fetch> fetch=n->new Fetch.Found(ticket(issue,Set.of("TEST-label")));
        java.util.function.IntFunction<WorkPage<LabelEvent>> audit=n->new WorkPage<>(List.of(event(1)),null);
        public Set<Capability> capabilities(){return Set.of(Capability.LABEL_AUDIT);}
        public WorkPage<WorkIssueLocation> candidates(String cursor){throw new UnsupportedOperationException();}
        public Fetch fetch(WorkIssueLocation ignored){return fetch.apply(++reads);}
        public WorkPage<LabelEvent> labelEvents(WorkIssueLocation ignored,String cursor){return audit.apply(++pages);}
        public String comment(WorkIssueLocation ignored,String text,String effect){throw new UnsupportedOperationException();}
        public void transition(WorkIssueLocation ignored,String transition,String effect){throw new UnsupportedOperationException();}
    }
    @Test void completeEvidenceCanAttribute(){Source source=new Source();WorkEvidence evidence=WorkEvidence.read(source,issue,null);assertNull(evidence.failure());assertEquals(LabelEvent.Origin.AUDIT_TRAIL,evidence.labels().getFirst().origin());assertEquals(2,source.reads);}
    @Test void missingFirstReadIsUnavailable(){Source source=new Source();source.fetch=n->new WorkSource.Fetch.Unavailable("TEST-outage");assertEquals("tracker_unavailable",WorkEvidence.read(source,issue,null).failure());}
    @Test void firstReadMustNameTheRequestedIdentity(){Source source=new Source();source.fetch=n->new WorkSource.Fetch.Found(ticket(otherIssue(),Set.of("TEST-label")));assertEquals("issue_identity_changed",WorkEvidence.read(source,issue,null).failure());}
    @Test void missingSecondReadIsUnavailable(){Source source=new Source();source.fetch=n->n==1?new WorkSource.Fetch.Found(ticket(issue,Set.of("TEST-label"))):new WorkSource.Fetch.Unavailable("TEST-outage");assertEquals("tracker_unavailable",WorkEvidence.read(source,issue,null).failure());}
    @Test void secondReadMustStillNameTheSameIdentity(){Source source=new Source();source.fetch=n->new WorkSource.Fetch.Found(ticket(n==1?issue:otherIssue(),Set.of("TEST-label")));assertEquals("tracker_changed_during_read",WorkEvidence.read(source,issue,null).failure());}
    @Test void changedLabelsRequireAnotherObservation(){Source source=new Source();source.fetch=n->new WorkSource.Fetch.Found(ticket(issue,n==1?Set.of("TEST-label"):Set.of()));assertEquals("tracker_changed_during_read",WorkEvidence.read(source,issue,null).failure());}
    @Test void anAuditOutageKeepsOnlyAnUnattributedAllowedHint(){Source source=new Source();source.audit=n->{throw new WorkSourceException("TEST-outage");};WorkEvidence evidence=WorkEvidence.read(source,issue,event(1));assertNull(evidence.failure());assertEquals(LabelEvent.Origin.UNATTRIBUTED,evidence.labels().getFirst().origin());assertEquals("900123",evidence.labels().getFirst().trackerActorId());}
    @Test void aTwentyFirstPageCannotProveAttribution(){Source source=new Source();source.audit=n->new WorkPage<>(List.of(event(n)),n==21?null:Integer.toString(n+1));WorkEvidence evidence=WorkEvidence.read(source,issue,null);assertEquals(LabelEvent.Origin.UNATTRIBUTED,evidence.labels().getFirst().origin());assertEquals(20,source.pages);}
    @Test void oversizedHistoryCannotProveAttribution(){Source source=new Source();source.audit=n->new WorkPage<>(java.util.stream.IntStream.rangeClosed(1,2001).mapToObj(this::event).toList(),null);assertEquals(LabelEvent.Origin.UNATTRIBUTED,WorkEvidence.read(source,issue,null).labels().getFirst().origin());}
    @Test void repeatedCursorCannotProveAttribution(){Source source=new Source();source.audit=n->new WorkPage<>(List.of(event(1)),n==3?null:"2");assertEquals(LabelEvent.Origin.UNATTRIBUTED,WorkEvidence.read(source,issue,null).labels().getFirst().origin());assertEquals(2,source.pages);}
    @Test void adapterConstructionFailureIsUnavailable(){assertEquals("tracker_unavailable",WorkEvidence.collect(()->{throw new WorkSourceException("TEST-private-error");},issue,null).failure());}
    WorkIssueLocation otherIssue(){return new WorkIssueLocation(new WorkIssueRef(ref.type(),ref.origin(),ref.projectId(),"50002"),"43",issue.link());}
}
