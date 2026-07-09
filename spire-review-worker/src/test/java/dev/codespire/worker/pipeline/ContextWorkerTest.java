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
import dev.codespire.context.confluence.ConfluenceLinks;
import dev.codespire.worker.adapters.PostgresBlobStore;
import dev.codespire.worker.adapters.WorkerContextClients;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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

    @Test
    void secondLevelResolvesRefsInsideLevelOneButStopsAtDepthTwo() {
        // AB-1 (from the PR) links CD-2 in its body; CD-2 links EF-3. We fetch AB-1 then CD-2, but STOP
        // before EF-3 — the depth cap is what breaks a jira→…→jira reference chain.
        KeyProvider jira = new KeyProvider("JIRA", Map.of(
                "AB-1", "see CD-2 for the design", "CD-2", "deeper still: EF-3", "EF-3", "must not be fetched"));
        clients.providers = List.of(jira);
        GatherContext command = new GatherContext("review::sandbox/demo-repo#7", REPO, 7, "abc123",
                Set.of("AB-1"), List.of(), null);

        worker.gatherContext(command);

        assertEquals(List.of("AB-1", "CD-2"), jira.fetched, "level 2 fetches CD-2; EF-3 is beyond the cap");
        ContextAssembled assembled = lastAssembled();
        assertEquals(Set.of("JIRA"), assembled.contributingSources());
        assertNotNull(assembled.contextRef(), "two items resolved → context persisted");
    }

    @Test
    void aConfluencePageLinkedFromBothThePrAndAFetchedTicketIsFetchedOnce() {
        // Scenario 3: the PR links Confluence page 123 AND references AB-1; AB-1's body links the SAME page.
        // The page is fetched once at level 1 and de-duplicated at level 2 — no second call.
        String page = "https://wiki.test/pages/123/Design";
        KeyProvider jira = new KeyProvider("JIRA", Map.of("AB-1", "related page " + page));
        LinkProvider confluence = new LinkProvider(Map.of("123", "the design doc"));
        clients.providers = List.of(jira, confluence);
        GatherContext command = new GatherContext("review::sandbox/demo-repo#7", REPO, 7, "abc123",
                Set.of("AB-1"), List.of(page), null);

        worker.gatherContext(command);

        assertEquals(List.of("123"), confluence.fetched, "the already-resolved page is not fetched again");
        assertEquals(List.of("AB-1"), jira.fetched);
        ContextAssembled assembled = lastAssembled();
        assertEquals(Set.of("JIRA", "CONFLUENCE"), assembled.contributingSources());
    }

    private ContextAssembled lastAssembled() {
        return assertInstanceOf(ContextAssembled.class, emitted.get(emitted.size() - 1));
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

    /** A Jira-like provider that resolves the request's ticketKeys into items, recording every key fetched. */
    private static final class KeyProvider implements ContextProvider {
        private final String source;
        private final Map<String, String> bodies; // key -> body (may itself carry the next reference)
        final List<String> fetched = new ArrayList<>();

        KeyProvider(String source, Map<String, String> bodies) {
            this.source = source;
            this.bodies = bodies;
        }

        @Override
        public String source() {
            return source;
        }

        @Override
        public boolean supports(ContextRequest request) {
            return request.ticketKeys() != null && !request.ticketKeys().isEmpty();
        }

        @Override
        public CompletionStage<ContextContribution> contribute(ContextRequest request) {
            List<ContextItem> items = new ArrayList<>();
            for (String key : request.ticketKeys()) {
                fetched.add(key);
                String body = bodies.get(key);
                if (body != null) {
                    items.add(new ContextItem("JIRA_TICKET", key, body, "jira/" + key));
                }
            }
            ContribStatus status = items.isEmpty() ? ContribStatus.EMPTY : ContribStatus.OK;
            return CompletableFuture.completedFuture(new ContextContribution(source, status, items, 1));
        }
    }

    /** A Confluence-like provider that resolves the page id in each link, recording every page fetched. */
    private static final class LinkProvider implements ContextProvider {
        private final Map<String, String> bodies; // pageId -> body
        final List<String> fetched = new ArrayList<>();

        LinkProvider(Map<String, String> bodies) {
            this.bodies = bodies;
        }

        @Override
        public String source() {
            return "CONFLUENCE";
        }

        @Override
        public boolean supports(ContextRequest request) {
            return request.links() != null
                    && request.links().stream().anyMatch(l -> ConfluenceLinks.pageId(l).isPresent());
        }

        @Override
        public CompletionStage<ContextContribution> contribute(ContextRequest request) {
            List<ContextItem> items = new ArrayList<>();
            for (String link : request.links()) {
                String pageId = ConfluenceLinks.pageId(link).orElse(null);
                if (pageId == null) {
                    continue;
                }
                fetched.add(pageId);
                String body = bodies.get(pageId);
                if (body != null) {
                    items.add(new ContextItem("CONFLUENCE_PAGE", "page " + pageId, body,
                            "https://wiki.test/pages/" + pageId));
                }
            }
            ContribStatus status = items.isEmpty() ? ContribStatus.EMPTY : ContribStatus.OK;
            return CompletableFuture.completedFuture(new ContextContribution("CONFLUENCE", status, items, 1));
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
