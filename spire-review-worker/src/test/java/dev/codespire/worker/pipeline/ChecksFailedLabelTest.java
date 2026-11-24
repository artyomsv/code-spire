package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.ActionCommand.LabelChecksFailed;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.VerdictLabel;
import dev.codespire.worker.adapters.WorkerScmClients;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Review waits for CI: a skipped head gets "review: ci-failed" once per skipped run, and only while it is current. */
class ChecksFailedLabelTest {

    private static final RepoRef REPO = new RepoRef("TEST-owner", "TEST-repo");
    private static final String HEAD = "a".repeat(40);
    private static final LabelChecksFailed COMMAND = new LabelChecksFailed("TEST-review", REPO, 7L, HEAD, 1, "TEST-cred");

    private final LabelRecordingSink sink = new LabelRecordingSink();
    private final InMemoryClaims claims = new InMemoryClaims();
    private String prHead = HEAD;

    @Test
    void theLabelReplacesTheOtherReviewLabelsAndNothingIsPosted() {
        sink.labels.addAll(List.of("review: requested", "TEST-unrelated"));
        label().apply(COMMAND);
        assertEquals(List.of(VerdictLabel.CI_FAILED.name()), sink.labelCalls);
        assertEquals(Set.of("TEST-unrelated", "review: ci-failed"), sink.labels);
        assertEquals(List.of("label"), sink.calls, "no comment, no review: the label is the only mark");
    }

    /** A replay after someone set another label since does not overwrite it: the run's attempt is recorded. */
    @Test
    void aReplayAfterAnotherLabelLabelsNothing() {
        label().apply(COMMAND);
        sink.labels.clear();
        sink.labels.add("review: requested");
        label().apply(COMMAND);
        assertEquals(List.of(VerdictLabel.CI_FAILED.name()), sink.labelCalls);
        assertEquals(Set.of("review: requested"), sink.labels);
    }

    /** A delayed command for a head the pull request moved past never labels the newer head. */
    @Test
    void aNewerHeadIsNotLabelled() {
        prHead = "b".repeat(40);
        label().apply(COMMAND);
        assertTrue(sink.labelCalls.isEmpty());
    }

    /** A re-run on the same commit that posted and labelled its review wins over the earlier run's skip. */
    @Test
    void aLaterReviewOfTheSameHeadThatLabelledWins() {
        claims.markPosted("TEST-review", HEAD, VerdictLabels.CLAIM_KEY, "applied");
        label().apply(new LabelChecksFailed("TEST-review", REPO, 7L, HEAD, 1, "TEST-cred"));
        assertTrue(sink.labelCalls.isEmpty());
    }

    /** Best effort: a forge failure is logged, never thrown, so the command is not dead-lettered. */
    @Test
    void aForgeFailureIsSwallowed() {
        sink.labelFailure = new IllegalStateException("TEST-forbidden");
        label().apply(COMMAND);
        assertTrue(sink.labels.isEmpty());
    }

    private ChecksFailedLabel label() {
        ChecksFailedLabel label = new ChecksFailedLabel();
        label.idempotency = claims;
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

    /** The real store's claim-then-mark semantics with no database. */
    private static final class InMemoryClaims extends CommentIdempotencyStore {
        private final Map<String, String> posted = new HashMap<>();

        @Override
        public Claim claim(String reviewId, String commit, String anchorKey) {
            String existing = posted.get(reviewId + "/" + commit + "/" + anchorKey);
            return existing != null ? new Claim.AlreadyPosted(existing) : new Claim.Post();
        }

        @Override
        public void markPosted(String reviewId, String commit, String anchorKey, String postedRef) {
            posted.put(reviewId + "/" + commit + "/" + anchorKey, postedRef);
        }

        @Override
        public Map<String, String> postedFor(String reviewId, String commit) {
            Map<String, String> found = new LinkedHashMap<>();
            String prefix = reviewId + "/" + commit + "/";
            posted.forEach((key, ref) -> {
                if (key.startsWith(prefix)) found.put(key.substring(prefix.length()), ref);
            });
            return found;
        }
    }
}
