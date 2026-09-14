package dev.codespire.contract.port;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.scm.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class PullRequestDraftContractTest {
    final PullRequestSink.NewPullRequest request=new PullRequestSink.NewPullRequest("spire/TEST-work","main","TEST title","TEST body",true);
    PullRequestSink unknownAdapter() { return new PullRequestSink() {
        public ScmType type(){return ScmType.GITHUB;}
        public PullRequestRef open(RepoRef repo,NewPullRequest value){throw new AssertionError("TEST adapter has no draft capability");}
        public Optional<PullRequestRef> findByHead(RepoRef repo,String head,String base){return Optional.empty();}
    }; }
    @Test void anUnknownAdapterCannotOfferDrafts(){assertFalse(unknownAdapter().supportsDrafts());}
    @Test void anUnsupportedDraftIsRefused(){assertThrows(PullRequestSink.DeliveryUnavailable.class,()->unknownAdapter().requireSupported(request));}
    @Test void unsupportedDraftsDoNotDisableRegularRequests(){assertDoesNotThrow(()->unknownAdapter().requireSupported(request.withDraft(false)));}
    @Test void aWitherPreservesTheCompleteRequest(){
        assertEquals(new PullRequestSink.NewPullRequest("spire/TEST-work","main","TEST title","TEST body",false),request.withDraft(false));
        assertEquals(request,request.withDraft(false).withDraft(true));
    }
    @Test void olderObservationsStayUnknownAndOlderRequestsStayRegular() throws Exception {
        var mapper=new ObjectMapper();
        assertNull(mapper.readValue("{\"number\":1,\"url\":\"https://forge.example.test/TEST/1\"}",PullRequestRef.class).draft());
        assertNull(new PullRequestRef(1,"https://forge.example.test/TEST/1").draft());
        assertFalse(mapper.readValue("{\"headBranch\":\"spire/TEST\",\"baseBranch\":\"main\",\"title\":\"TEST\",\"bodyMd\":\"TEST\"}",PullRequestSink.NewPullRequest.class).draft());
    }
    @Test void aRegularObservationCannotSatisfyDraftDelivery(){
        assertThrows(PullRequestSink.DeliveryUnavailable.class,()->PullRequestSink.requireObservedDraft(request,new PullRequestRef(1,"https://forge.example.test/TEST/1",false)));
    }
    @Test void anUnknownObservationCannotSatisfyDraftDelivery(){
        assertThrows(PullRequestSink.DeliveryUnavailable.class,()->PullRequestSink.requireObservedDraft(request,new PullRequestRef(1,"https://forge.example.test/TEST/1")));
    }
    @Test void theStoredDraftRequestAndObservationKeepTheirShape() throws Exception {
        var mapper=new ObjectMapper();
        assertEquals("{\"headBranch\":\"spire/TEST-work\",\"baseBranch\":\"main\",\"title\":\"TEST title\",\"bodyMd\":\"TEST body\",\"draft\":true}",mapper.writeValueAsString(request));
        var ref=new PullRequestRef(1,"https://forge.example.test/TEST/1",true);
        assertEquals("{\"number\":1,\"url\":\"https://forge.example.test/TEST/1\",\"draft\":true}",mapper.writeValueAsString(ref));
        assertEquals(request,mapper.readValue(mapper.writeValueAsBytes(request),PullRequestSink.NewPullRequest.class));
        assertEquals(ref,mapper.readValue(mapper.writeValueAsBytes(ref),PullRequestRef.class));
    }
}
