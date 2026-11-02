package dev.codespire.worker.pipeline;

import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadTranscript;

import java.util.List;
import java.util.Map;

/**
 * The reconcile steps both review paths share (harness reviews, step 6): compare, remap renames, thread transcripts,
 * the verdict backstops and the anchor-collision filter. The API path's {@code ReviewWorker} and the harness path's
 * {@code HarnessReviewWorker} call the same code, so a harness re-review keeps the API path's deterministic backstops.
 */
final class ReconcileSteps {

    private ReconcileSteps() {
    }

    /** The pull request and the head a reconcile step reads. */
    record ReviewAt(RepoRef repo, long prId, String commit) {
    }

    /** The incremental diff (null: the full-diff fallback) and the prior findings remapped through its renames. */
    record Basis(String incrementalDiff, List<PriorFinding> remapped) {
    }

    static Basis basis(DiffSource source, ReviewAt at, PriorRun prior) {
        throw new UnsupportedOperationException("not built yet");
    }

    static String compareOrNull(DiffSource source, ReviewAt at, String priorHead) {
        throw new UnsupportedOperationException("not built yet");
    }

    static Map<String, ThreadTranscript> transcripts(CommentSink comments, ReviewAt at, List<PriorFinding> findings) {
        throw new UnsupportedOperationException("not built yet");
    }

    static List<FindingVerdict> judge(String answerText, List<PriorFinding> numbered, String incrementalDiff) {
        throw new UnsupportedOperationException("not built yet");
    }

    static ReviewResult dropAnchorCollisions(ReviewResult result, List<FindingVerdict> verdicts) {
        throw new UnsupportedOperationException("not built yet");
    }
}
