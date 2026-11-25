package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ActionCommand.LabelChecksFailed;
import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.VerdictLabel;
import dev.codespire.worker.adapters.WorkerScmClients;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Review waits for CI: a skipped run gets "review: ci-failed" once, only while its head is current and no newer run
 * was admitted. The receipt store follows the real SQL ({@link ReceiptStore}), and the orchestrator's open and close
 * are replayed in the order a real re-run makes them.
 */
class ChecksFailedLabelTest {

    private static final RepoRef REPO = new RepoRef("TEST-owner", "TEST-repo");
    private static final String HEAD = "a".repeat(40);
    private static final String REVIEW = "TEST-review";
    private static final String SLOT = ChecksFailedLabel.CLAIM_PREFIX + 1;
    private static final LabelChecksFailed COMMAND = new LabelChecksFailed(REVIEW, REPO, 7L, HEAD, 1, "TEST-cred");

    private final ReceiptStore receipts = new ReceiptStore();
    private Runnable duringLabel = () -> { };
    private final LabelRecordingSink sink = new LabelRecordingSink() {
        @Override
        public CommentSink.LabelOutcome setVerdictLabel(RepoRef repo, long prId, VerdictLabel label) {
            duringLabel.run();
            return super.setVerdictLabel(repo, prId, label);
        }
    };
    private String prHead = HEAD;

    @Test
    void anOpenedRunIsLabelledOnceAndNothingIsPosted() {
        receipts.open(REVIEW, HEAD, SLOT);
        sink.labels.addAll(List.of("review: requested", "TEST-unrelated"));
        label().apply(COMMAND);
        assertEquals(Set.of("TEST-unrelated", "review: ci-failed"), sink.labels);
        assertEquals(List.of("label"), sink.calls, "no comment, no review: the label is the only mark");
        assertEquals("APPLIED", receipts.refOf(REVIEW, HEAD, SLOT));
    }

    /** A replay after someone set another label since does not overwrite it. */
    @Test
    void aReplayAfterAnotherLabelLabelsNothing() {
        receipts.open(REVIEW, HEAD, SLOT);
        label().apply(COMMAND);
        sink.labels.clear();
        sink.labels.add("review: requested");
        label().apply(COMMAND);
        assertEquals(List.of(VerdictLabel.CI_FAILED.name()), sink.labelCalls);
        assertEquals(Set.of("review: requested"), sink.labels);
    }

    /**
     * The real re-run order: the skip opens run 1's receipt and sends the command; the re-run's admission (round lock)
     * closes every open receipt; only then does the delayed command arrive. The head is the same; nothing is labelled.
     */
    @Test
    void aRerunAdmittedBeforeTheOldCommandArrivesLabelsNothing() {
        receipts.open(REVIEW, HEAD, SLOT);
        receipts.supersede(REVIEW);
        sink.labels.add("TEST-operator-label");
        label().apply(COMMAND);
        assertTrue(sink.labelCalls.isEmpty());
        assertEquals(Set.of("TEST-operator-label"), sink.labels);
        assertEquals("superseded", receipts.refOf(REVIEW, HEAD, SLOT));
    }

    /** The worker took the receipt first: a re-run admitted during the forge call closes nothing it took. */
    @Test
    void aTakenReceiptIsNeitherClosedNorOverwritten() {
        receipts.open(REVIEW, HEAD, SLOT);
        duringLabel = () -> receipts.supersede(REVIEW);
        label().apply(COMMAND);
        assertEquals(1, sink.labelCalls.size());
        assertEquals("APPLIED", receipts.refOf(REVIEW, HEAD, SLOT), "the outcome is recorded over its own mark only");
    }

    /** A command whose receipt the orchestrator never opened labels nothing. */
    @Test
    void anUnopenedRunIsNotLabelled() {
        label().apply(COMMAND);
        assertTrue(sink.labelCalls.isEmpty());
    }

    /** A delayed command for a head the pull request moved past never labels the newer head. */
    @Test
    void aNewerHeadIsNotLabelled() {
        receipts.open(REVIEW, HEAD, SLOT);
        prHead = "b".repeat(40);
        label().apply(COMMAND);
        assertTrue(sink.labelCalls.isEmpty());
    }

    /** A re-run on the same commit that posted and labelled its review wins over the earlier run's skip. */
    @Test
    void aLaterReviewOfTheSameHeadThatLabelledWins() {
        receipts.open(REVIEW, HEAD, SLOT);
        receipts.markPosted(REVIEW, HEAD, VerdictLabels.CLAIM_KEY, "applied");
        label().apply(COMMAND);
        assertTrue(sink.labelCalls.isEmpty());
    }

    /** Best effort: a forge failure is logged, never thrown, so the command is not dead-lettered. */
    @Test
    void aForgeFailureIsSwallowed() {
        receipts.open(REVIEW, HEAD, SLOT);
        sink.labelFailure = new IllegalStateException("TEST-forbidden");
        label().apply(COMMAND);
        assertTrue(sink.labels.isEmpty());
        assertEquals(CommentIdempotencyStore.TAKEN, receipts.refOf(REVIEW, HEAD, SLOT));
    }

    private ChecksFailedLabel label() {
        ChecksFailedLabel label = new ChecksFailedLabel();
        label.idempotency = receipts;
        label.scm = new WorkerScmClients() {
            @Override
            public Clients forCommand(ActionCommand command) {
                return new Clients(new HeadOnly(), sink);
            }
        };
        return label;
    }

    /** The pull request's head, the only read the label makes. */
    private final class HeadOnly implements DiffSource {
        @Override
        public ScmType type() {
            return ScmType.GITHUB;
        }

        @Override
        public String apiHost() {
            return "forge.example.test";
        }

        @Override
        public PullRequest fetchPullRequest(RepoRef repo, long prId) {
            return new PullRequest(repo, prId, "TEST-title", "", "TEST-source", "main", prHead,
                    Author.of("TEST-id", "TEST-user", "TEST-user"), "https://forge.example.test/TEST/7");
        }

        @Override
        public Diff fetchDiff(RepoRef repo, long prId, String commit) {
            throw new AssertionError("a label never reads the diff");
        }
    }
}
