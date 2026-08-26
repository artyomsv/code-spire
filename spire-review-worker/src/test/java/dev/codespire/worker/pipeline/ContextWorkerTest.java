package dev.codespire.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand.GatherContext;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.ContextAssembled;
import dev.codespire.contract.event.IntegrationEvent.ContextContributed;
import dev.codespire.contract.event.IntegrationEvent.ContextRequested;
import dev.codespire.contract.port.BlobStore;
import dev.codespire.contract.port.ContextProvider;
import dev.codespire.contract.port.FirstLevelOnly;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.CodeReferences;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.review.ContribStatus;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.context.confluence.ConfluenceLinks;
import dev.codespire.context.github.GitHubIssueRefs;
import dev.codespire.worker.adapters.PostgresBlobStore;
import dev.codespire.worker.adapters.RulesContextProvider;
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
            Set.of("CS-42", "https://issue/42"), null, null, null);

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
        // The real extractors: reference discovery across levels is what these tests exercise.
        worker.contextReferences = new dev.codespire.worker.adapters.WorkerContextReferences();
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
                Set.of("AB-1"), null, null, null);

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
                Set.of("AB-1", page), null, null, null);

        worker.gatherContext(command);

        assertEquals(List.of("123"), confluence.fetched, "the already-resolved page is not fetched again");
        assertEquals(List.of("AB-1"), jira.fetched);
        ContextAssembled assembled = lastAssembled();
        assertEquals(Set.of("JIRA", "CONFLUENCE"), assembled.contributingSources());
    }

    @Test
    void secondLevelResolvesABareIssueReferenceInsideAFetchedIssue() {
        // The PR names issue #1 as its root cause; #1's body points at #2. Both must reach the prompt —
        // a bare reference found inside retrieved text still resolves against the review's own repository.
        IssueProvider github = new IssueProvider(Map.of(1, "Root cause is tracked in #2", 2, "The cap is 50"));
        clients.providers = List.of(github);

        worker.gatherContext(githubCommand(Set.of("#1")));

        assertEquals(List.of(1, 2), github.fetched, "level 2 resolves the issue linked from issue #1");
        assertEquals(Set.of("GITHUB_ISSUES"), lastAssembled().contributingSources());
    }

    @Test
    void aReferenceLeftInAnIssueCommentIsResolvedLikeOneInTheBody() {
        // Providers append comments to the item body, so the corpus the next level mines already
        // contains them: a link a reviewer left in a comment resolves like one in the description.
        IssueProvider github = new IssueProvider(Map.of(
                1, "State: open\n\nNothing linked here.\n\nRecent comments:\n- ana: superseded by #2",
                2, "The cap is 50"));
        clients.providers = List.of(github);

        worker.gatherContext(githubCommand(Set.of("#1")));

        assertEquals(List.of(1, 2), github.fetched, "a reference inside a comment reaches level 2");
    }

    @Test
    void aCycleBetweenTwoIssuesTerminatesWithEachIssueFetchedOnce() {
        // #1 links #2 and #2 links back to #1. The depth cap ends collection; dedup is what keeps an
        // already-retrieved issue from being fetched again — including from the html_url it carries itself.
        IssueProvider github = new IssueProvider(Map.of(1, "See #2", 2, "Caused by #1"));
        clients.providers = List.of(github);

        worker.gatherContext(githubCommand(Set.of("#1")));

        assertEquals(List.of(1, 2), github.fetched, "a reference cycle costs one fetch per issue, not more");
        assertEquals(2, itemsIn(lastAssembled()), "both issues reach the prompt, neither twice");
    }

    @Test
    void codeReferencesAloneFanOutWithNoTicketReference() {
        // No ticket key anywhere on the PR (references empty) — only the diff's own symbols, carried
        // on codeReferences instead. Before the fix, `collect` broke on `next.isEmpty()` before level 1
        // ever ran, so the code provider (and the rules provider — see the next test) never fired on
        // the majority of pull requests, which do not mention a ticket.
        CodeLikeProvider code = new CodeLikeProvider();
        clients.providers = List.of(code);
        CodeReferences codeReferences = new CodeReferences(
                Set.of("src/main/java/dev/example/Alpha.java"), Set.of("Pricer", "chargeFor"));
        GatherContext command = new GatherContext("review::sandbox/demo-repo#7", REPO, 7, "abc123",
                Set.of(), null, null, null, codeReferences);

        worker.gatherContext(command);

        assertTrue(code.contributed, "the code provider must be invoked even with no ticket reference");
        assertEquals(Set.of("CODE"), lastAssembled().contributingSources());
    }

    @Test
    void aFirstLevelOnlyProviderContributesOnlyAtLevelOneEvenWhenATicketTriggersLevelTwo() {
        // I1, rung-1 final review: codeReferences rides unchanged onto every level's request, so
        // without the FirstLevelOnly gate the code provider's supports() would report true again at
        // level 2 whenever the PR also carries a ticket that discovers a fresh reference — re-running
        // its whole fetch-and-extract pipeline a second time for zero new information.
        CountingFirstLevelOnlyProvider code = new CountingFirstLevelOnlyProvider();
        KeyProvider jira = new KeyProvider("JIRA", Map.of(
                "AB-1", "see CD-2 for the design", "CD-2", "no further reference in here"));
        clients.providers = List.of(code, jira);
        CodeReferences codeReferences = new CodeReferences(
                Set.of("src/main/java/dev/example/Alpha.java"), Set.of("Pricer", "chargeFor"));
        GatherContext command = new GatherContext("review::sandbox/demo-repo#7", REPO, 7, "abc123",
                Set.of("AB-1"), null, null, null, codeReferences);

        worker.gatherContext(command);

        assertEquals(1, code.invocations, "must not re-run at level 2 even though level 2 does run");
        assertEquals(List.of("AB-1", "CD-2"), jira.fetched, "level 2 still runs for the ticket-based provider");
    }

    @Test
    void repoRulesAloneFanOutWithNoTicketReference() {
        // Pins the shipped repo-rules feature (a real defect this same fix closes, not a new one):
        // RulesContextProvider.supports() depends only on repoRules, never on references, but the
        // pre-fix loop broke before it was ever consulted.
        clients.providers = List.of(new RulesContextProvider());
        GatherContext command = new GatherContext("review::sandbox/demo-repo#7", REPO, 7, "abc123",
                Set.of(), null, null, "use 4-space indent");

        worker.gatherContext(command);

        assertEquals(Set.of("RULES"), lastAssembled().contributingSources());
    }

    @Test
    void levelTwoDoesNotRunWhenNoFreshReferencesAreDiscovered() {
        // The bound the fix must not loosen: level 1 now runs unconditionally, but level 2 still runs
        // only when level 1 (or a later level) actually discovered a fresh reference to chase.
        KeyProvider jira = new KeyProvider("JIRA", Map.of("AB-1", "no further reference in here"));
        clients.providers = List.of(jira);
        GatherContext command = new GatherContext("review::sandbox/demo-repo#7", REPO, 7, "abc123",
                Set.of("AB-1"), null, null, null);

        worker.gatherContext(command);

        assertEquals(List.of("AB-1"), jira.fetched, "exactly one fetch — level 2 never ran");
    }

    private static GatherContext githubCommand(Set<String> references) {
        return new GatherContext("review::sandbox/demo-repo#7", REPO, 7, "abc123",
                references, null, ScmType.GITHUB, null);
    }

    /** Items the assembled context actually carries, counted from the merged Contributed events. */
    private int itemsIn(ContextAssembled assembled) {
        return (int) emitted.stream()
                .filter(e -> e instanceof ContextContributed)
                .map(e -> ((ContextContributed) e).contribution())
                .filter(c -> assembled.contributingSources().contains(c.source()))
                .flatMap(c -> c.items().stream())
                .map(ContextItem::uri)
                .distinct()
                .count();
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

    /**
     * A code-provider stand-in: {@code supports} depends only on {@code codeReferences}, exactly like
     * the real {@code CodeContextProvider}, and never on {@code references} — the property that
     * {@code collect}'s level-1 fix must let through.
     */
    private static final class CodeLikeProvider implements ContextProvider {
        boolean contributed;

        @Override
        public String source() {
            return "CODE";
        }

        @Override
        public boolean supports(ContextRequest request) {
            return !request.codeReferences().isEmpty();
        }

        @Override
        public CompletionStage<ContextContribution> contribute(ContextRequest request) {
            contributed = true;
            ContextItem item = new ContextItem("CODE_SNIPPET", "Pricer.chargeFor",
                    "long chargeFor(long tokens) { return tokens; }",
                    "src/main/java/dev/example/pricing/Pricer.java");
            return CompletableFuture.completedFuture(
                    new ContextContribution("CODE", ContribStatus.OK, List.of(item), 1));
        }
    }

    /**
     * Like {@link CodeLikeProvider}, but also implements {@link FirstLevelOnly} and counts every
     * invocation — proves {@code collect} excludes a {@code FirstLevelOnly} provider from level 2+
     * (I1, rung-1 final review).
     */
    private static final class CountingFirstLevelOnlyProvider implements ContextProvider, FirstLevelOnly {
        int invocations;

        @Override
        public String source() {
            return "CODE";
        }

        @Override
        public boolean supports(ContextRequest request) {
            return !request.codeReferences().isEmpty();
        }

        @Override
        public CompletionStage<ContextContribution> contribute(ContextRequest request) {
            invocations++;
            ContextItem item = new ContextItem("CODE_SNIPPET", "Pricer.chargeFor",
                    "long chargeFor(long tokens) { return tokens; }",
                    "src/main/java/dev/example/pricing/Pricer.java");
            return CompletableFuture.completedFuture(
                    new ContextContribution("CODE", ContribStatus.OK, List.of(item), 1));
        }
    }

    /**
     * A Jira-like provider that resolves issue-key references into items, recording every key fetched.
     * The request carries every source's candidates, so it narrows to the ones shaped like a key —
     * the same job a real provider does.
     */
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
            return request.references() != null && !matching(request).isEmpty();
        }

        @Override
        public CompletionStage<ContextContribution> contribute(ContextRequest request) {
            List<ContextItem> items = new ArrayList<>();
            for (String key : matching(request)) {
                fetched.add(key);
                String body = bodies.get(key);
                if (body != null) {
                    items.add(new ContextItem("JIRA_TICKET", key, body, "jira/" + key));
                }
            }
            ContribStatus status = items.isEmpty() ? ContribStatus.EMPTY : ContribStatus.OK;
            return CompletableFuture.completedFuture(new ContextContribution(source, status, items, 1));
        }

        /** Candidates shaped like an issue key — anything else belongs to another source. */
        private static List<String> matching(ContextRequest request) {
            return request.references() == null ? List.of()
                    : request.references().stream().filter(r -> r.matches("[A-Z][A-Z0-9]+-\\d+")).toList();
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
            return request.references() != null
                    && request.references().stream().anyMatch(l -> ConfluenceLinks.pageId(l).isPresent());
        }

        @Override
        public CompletionStage<ContextContribution> contribute(ContextRequest request) {
            List<ContextItem> items = new ArrayList<>();
            for (String link : request.references()) {
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

    /**
     * A GitHub-issues-like provider: a bare {@code #N} resolves against the review's own repository
     * (and only when the review runs on GitHub), and every item carries the {@code html_url} the real
     * provider carries — which is what makes it a faithful stand-in for cross-level dedup.
     */
    private static final class IssueProvider implements ContextProvider {
        private static final String ISSUE_URL = "https://github.com/sandbox/demo-repo/issues/";

        private final Map<Integer, String> bodies; // issue number -> body (may carry the next reference)
        final List<Integer> fetched = new ArrayList<>();

        IssueProvider(Map<Integer, String> bodies) {
            this.bodies = bodies;
        }

        @Override
        public String source() {
            return "GITHUB_ISSUES";
        }

        @Override
        public boolean supports(ContextRequest request) {
            return !targets(request).isEmpty();
        }

        @Override
        public CompletionStage<ContextContribution> contribute(ContextRequest request) {
            List<ContextItem> items = new ArrayList<>();
            for (int number : targets(request)) {
                fetched.add(number);
                String body = bodies.get(number);
                if (body != null) {
                    items.add(new ContextItem("ISSUE", "#" + number + " — Issue " + number, body,
                            ISSUE_URL + number));
                }
            }
            ContribStatus status = items.isEmpty() ? ContribStatus.EMPTY : ContribStatus.OK;
            return CompletableFuture.completedFuture(
                    new ContextContribution("GITHUB_ISSUES", status, items, 1));
        }

        /** Issue numbers this request resolves to, deduped the way the real provider dedupes targets. */
        private static List<Integer> targets(ContextRequest request) {
            if (request.references() == null || request.scmType() != ScmType.GITHUB) {
                return List.of();
            }
            List<Integer> numbers = new ArrayList<>();
            for (String reference : request.references()) {
                GitHubIssueRefs.parse(reference)
                        .filter(ref -> ref.isRepoRelative() || REPO.slug().equals(ref.repo()))
                        .map(GitHubIssueRefs.Ref::number)
                        .filter(number -> !numbers.contains(number))
                        .ifPresent(numbers::add);
            }
            return numbers;
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
