package dev.codespire.worker.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand.GatherContext;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.port.BlobStore;
import dev.codespire.contract.port.ContextProvider;
import dev.codespire.contract.review.ContextContribution;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.ContextRequest;
import dev.codespire.contract.review.ContribStatus;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.worker.adapters.PostgresBlobStore;
import dev.codespire.worker.adapters.WorkerContextClients;
import dev.codespire.worker.adapters.WorkerContextReferences;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link ContextWorker#collect}'s level-2 mining reads level-1 item BODIES for new references (a
 * ticket linking a wiki page, a wiki page linking another ticket, and so on). A {@code CODE_SNIPPET}
 * body is source code, so a ticket-shaped string sitting inside a code comment — {@code // see
 * PROJ-123 for background} — must never be read as a genuine reference: that would turn an ordinary
 * comment into a live fetch against a system nobody mentioned in the pull request.
 *
 * <p>Drives {@link ContextWorker#gatherContext} end to end (the collection method itself is private)
 * with a fake CODE-like provider standing in for {@link dev.codespire.context.code.CodeContextProvider}
 * and a fake JIRA-like provider that records every issue key it is actually asked to resolve — the
 * same harness shape {@code ContextWorkerTest} already uses for its own cross-level scenarios.
 */
class ContextWorkerCodeExclusionTest {

    private static final RepoRef REPO = new RepoRef("sandbox", "demo-repo");
    // A non-empty text reference is required so ContextWorker.collect's level-1 loop actually runs —
    // the code provider's own contribution is gated on codeReferences, not on this set, but the
    // aggregator's outer loop starts from `references` regardless of which provider will use it.
    private static final GatherContext COMMAND = new GatherContext(
            "review::sandbox/demo-repo#7", REPO, 7, "abc123", Set.of("AB-1"), null, null, null);

    private ContextWorker worker;

    @BeforeEach
    void setUp() {
        worker = new ContextWorker();
        worker.contextReferences = new WorkerContextReferences();
        worker.blobStore = new RecordingBlobStore();
        worker.mapper = new ObjectMapper();
        worker.results = new ResultsEmitter() {
            @Override
            public void emit(IntegrationEvent event) {
                // nothing to record here — this test only inspects the fake providers' own state
            }
        };
    }

    @Test
    void aTicketKeyInsideACodeCommentIsNotMinedAsAReference() {
        CodeSnippetProvider code = new CodeSnippetProvider(
                "// see PROJ-123 for background\nlong alpha() { return 1; }");
        RecordingKeyProvider jira = new RecordingKeyProvider();
        worker.contextClients = new FakeContextClients(List.of(code, jira));

        worker.gatherContext(COMMAND);

        assertTrue(jira.fetched.contains("AB-1"), "the PR's own reference is resolved normally");
        assertFalse(jira.fetched.contains("PROJ-123"),
                "a ticket-shaped string inside a CODE_SNIPPET body must never be mined as a reference");
    }

    // --- test doubles -------------------------------------------------------

    private static final class FakeContextClients extends WorkerContextClients {
        private final List<ContextProvider> providers;

        FakeContextClients(List<ContextProvider> providers) {
            this.providers = providers;
        }

        @Override
        public List<ContextProvider> forCommand(GatherContext command) {
            return providers;
        }
    }

    /**
     * Stands in for CodeContextProvider's CODE_SNIPPET shape, but — unlike the real provider, which
     * implements {@code FirstLevelOnly} and is gated to level 1 (I1, rung-1 final review) —
     * deliberately supports every level, so this test exercises corpus exclusion regardless of which
     * level actually runs it.
     */
    private static final class CodeSnippetProvider implements ContextProvider {
        private final String body;

        CodeSnippetProvider(String body) {
            this.body = body;
        }

        @Override
        public String source() {
            return "CODE";
        }

        @Override
        public boolean supports(ContextRequest request) {
            return true; // the real provider gates on codeReferences, irrelevant to this scenario
        }

        @Override
        public CompletionStage<ContextContribution> contribute(ContextRequest request) {
            ContextItem item = new ContextItem(ContextItem.CODE_SNIPPET, "alpha — src/Alpha.java", body,
                    "src/Alpha.java");
            return CompletableFuture.completedFuture(
                    new ContextContribution("CODE", ContribStatus.OK, List.of(item), 1));
        }
    }

    /** Stands in for a Jira-like provider: records every issue key it was actually asked to resolve. */
    private static final class RecordingKeyProvider implements ContextProvider {
        final List<String> fetched = new ArrayList<>();

        @Override
        public String source() {
            return "JIRA";
        }

        @Override
        public boolean supports(ContextRequest request) {
            return request.references() != null && !matching(request).isEmpty();
        }

        @Override
        public CompletionStage<ContextContribution> contribute(ContextRequest request) {
            List<String> keys = matching(request);
            fetched.addAll(keys);
            List<ContextItem> items = keys.stream()
                    .map(key -> new ContextItem("JIRA_TICKET", key, "just a ticket", "jira/" + key))
                    .toList();
            ContribStatus status = items.isEmpty() ? ContribStatus.EMPTY : ContribStatus.OK;
            return CompletableFuture.completedFuture(new ContextContribution("JIRA", status, items, 1));
        }

        private static List<String> matching(ContextRequest request) {
            return request.references() == null ? List.of()
                    : request.references().stream().filter(r -> r.matches("[A-Z][A-Z0-9]+-\\d+")).toList();
        }
    }

    private static final class RecordingBlobStore extends PostgresBlobStore {
        private int counter;

        @Override
        public BlobStore.BlobRef put(BlobStore.Kind kind, String reviewId, byte[] plaintext) {
            return new BlobStore.BlobRef("blob-" + (++counter));
        }

        @Override
        public int deleteByReview(String reviewId) {
            return 0;
        }
    }
}
