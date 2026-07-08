package dev.codespire.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand.GatherContext;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.ContextAssembled;
import dev.codespire.contract.event.IntegrationEvent.ContextContributed;
import dev.codespire.contract.event.IntegrationEvent.ContextRequested;
import dev.codespire.contract.port.BlobStore;
import dev.codespire.contract.port.ContextProvider;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.review.ContribStatus;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.worker.adapters.PostgresBlobStore;
import dev.codespire.worker.adapters.WorkerContextClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * ContextWorker aggregator unit tests: fan out to the supported providers under a
 * bounded budget, emit Requested -> Contributed(*) -> Assembled, persist the
 * assembled context to the BlobStore only when there are items, and record which
 * sources contributed vs went missing. An emit failure propagates so the incoming
 * command is nacked to cs.dlq instead of half-completing silently.
 */
class ContextWorkerTest {

    private static final RepoRef REPO = new RepoRef("sandbox", "demo-repo");
    private static final GatherContext COMMAND = new GatherContext(
            "review::sandbox/demo-repo#7", REPO, 7, "abc123",
            Set.of("CS-42"), List.of("https://issue/42"), null);

    private ContextWorker worker;
    private List<IntegrationEvent> emitted;
    private FakeContextClients clients;
    private RecordingBlobStore blobStore;
    private RuntimeException failAfter;
    private int failOnEmitNumber;

    @BeforeEach
    void setUp() {
        emitted = new ArrayList<>();
        failAfter = null;
        failOnEmitNumber = -1;
        clients = new FakeContextClients();
        blobStore = new RecordingBlobStore();
        worker = new ContextWorker();
        worker.contextClients = clients;
        worker.blobStore = blobStore;
        worker.mapper = new ObjectMapper();
        worker.results = new ResultsEmitter() {
            @Override
            public void emit(IntegrationEvent event) {
                emitted.add(event);
                if (failAfter != null && emitted.size() == failOnEmitNumber) {
                    throw failAfter;
                }
            }
        };
    }

    @Test
    void noProvidersEmitsRequestedThenEmptyAssembled() {
        worker.gatherContext(COMMAND);
        assertEquals(2, emitted.size(), "no provider contributed → Requested then Assembled, no Contributed");
        assertInstanceOf(ContextRequested.class, emitted.get(0));
        ContextAssembled assembled = assertInstanceOf(ContextAssembled.class, emitted.get(1));
        assertNull(assembled.contextRef(), "nothing to persist → no blob, null ref");
        assertTrue(assembled.contributingSources().isEmpty());
        assertTrue(assembled.missingSources().isEmpty());
        assertEquals(Set.of(), requestedOf(emitted.get(0)).expectedSources());
    }

    @Test
    void oneOkProviderContributesPersistsAndAssembles() {
        clients.providers = List.of(new FakeProvider("JIRA", true, ok("JIRA")));
        worker.gatherContext(COMMAND);

        assertEquals(3, emitted.size());
        assertInstanceOf(ContextRequested.class, emitted.get(0));
        ContextContributed contributed = assertInstanceOf(ContextContributed.class, emitted.get(1));
        ContextAssembled assembled = assertInstanceOf(ContextAssembled.class, emitted.get(2));

        assertEquals(Set.of("JIRA"), requestedOf(emitted.get(0)).expectedSources());
        assertEquals("JIRA", contributed.contribution().source());
        assertEquals(ContribStatus.OK, contributed.contribution().status());
        assertEquals(Set.of("JIRA"), assembled.contributingSources());
        assertTrue(assembled.missingSources().isEmpty());
        assertNotNull(assembled.contextRef(), "items present → context persisted → ref set");
        assertEquals(1, blobStore.puts, "the assembled context is written exactly once");
        assertEquals(assembled.contextRef(), blobStore.lastRef.key());
    }

    @Test
    void unsupportedProviderIsNeitherExpectedNorInvoked() {
        FakeProvider skipped = new FakeProvider("JIRA", false, ok("JIRA"));
        clients.providers = List.of(skipped);
        worker.gatherContext(COMMAND);

        assertEquals(Set.of(), requestedOf(emitted.get(0)).expectedSources());
        assertFalse(skipped.contributed, "a provider that does not support the request is never called");
        assertInstanceOf(ContextAssembled.class, emitted.get(emitted.size() - 1));
    }

    @Test
    void failedProviderIsRecordedErrorAndReportedMissing() {
        clients.providers = List.of(new FakeProvider("JIRA", true, null)); // null result => failed future
        worker.gatherContext(COMMAND);

        ContextContributed contributed = assertInstanceOf(ContextContributed.class, emitted.get(1));
        assertEquals(ContribStatus.ERROR, contributed.contribution().status());
        ContextAssembled assembled = assertInstanceOf(ContextAssembled.class, emitted.get(2));
        assertTrue(assembled.contributingSources().isEmpty());
        assertEquals(Set.of("JIRA"), assembled.missingSources());
        assertNull(assembled.contextRef(), "no items → nothing persisted");
        assertEquals(0, blobStore.puts);
    }

    @Test
    void redeliveryClearsPriorBlobsBeforeAssembling() {
        clients.providers = List.of(new FakeProvider("JIRA", true, ok("JIRA")));
        worker.gatherContext(COMMAND);
        assertEquals(1, blobStore.deleteByReviewCalls, "a re-delivered command must clear prior blobs first");
    }

    @Test
    void emitFailureOnTheFirstEventPropagates() {
        failAfter = new CompletionException(new IllegalStateException("broker nack"));
        failOnEmitNumber = 1;
        assertThrows(CompletionException.class, () -> worker.gatherContext(COMMAND),
                "a nacked publish must fail the @Incoming handler so the command lands on cs.dlq");
        assertEquals(1, emitted.size(), "nothing after the failed emit");
    }

    private static ContextRequest requestedOf(IntegrationEvent event) {
        return assertInstanceOf(ContextRequested.class, event).request();
    }

    private static ContextContribution ok(String source) {
        return new ContextContribution(source, ContribStatus.OK,
                List.of(new ContextItem("JIRA_TICKET", "CS-42 — Fix it", "body", "https://issue/42")), 5);
    }

    // --- test doubles -------------------------------------------------------

    private static final class FakeContextClients extends WorkerContextClients {
        List<ContextProvider> providers = List.of();

        @Override
        public List<ContextProvider> forCommand(GatherContext command) {
            return providers;
        }
    }

    /** A provider whose contribution is fixed; a null result yields a failed future (error path). */
    private static final class FakeProvider implements ContextProvider {
        private final String source;
        private final boolean supports;
        private final ContextContribution result;
        boolean contributed;

        FakeProvider(String source, boolean supports, ContextContribution result) {
            this.source = source;
            this.supports = supports;
            this.result = result;
        }

        @Override
        public String source() {
            return source;
        }

        @Override
        public boolean supports(ContextRequest request) {
            return supports;
        }

        @Override
        public CompletionStage<ContextContribution> contribute(ContextRequest request) {
            contributed = true;
            return result == null
                    ? CompletableFuture.failedFuture(new IllegalStateException("provider blew up"))
                    : CompletableFuture.completedFuture(result);
        }
    }

    private static final class RecordingBlobStore extends PostgresBlobStore {
        int puts;
        int deleteByReviewCalls;
        BlobStore.BlobRef lastRef;

        @Override
        public BlobStore.BlobRef put(BlobStore.Kind kind, String reviewId, byte[] plaintext) {
            puts++;
            lastRef = new BlobStore.BlobRef("blob-" + puts);
            return lastRef;
        }

        @Override
        public int deleteByReview(String reviewId) {
            deleteByReviewCalls++;
            return 0;
        }
    }
}
