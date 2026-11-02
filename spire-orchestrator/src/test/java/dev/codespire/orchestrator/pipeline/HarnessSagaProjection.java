package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.ReviewVerdict;
import dev.codespire.orchestrator.TestRepositoryProjection;
import dev.codespire.orchestrator.llm.ChargeCall;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The review read model as {@link ResultSagaHarnessReviewTest} sees it: what the saga wrote, recorded, and nothing
 * stored. Every method the ContextAssembled, ReviewGenerated and refusal paths reach is overridden, because the real one
 * opens a database from a unit test (CLAUDE.md, saga test fakes).
 */
class HarnessSagaProjection extends TestRepositoryProjection {

    final List<String> statuses = new ArrayList<>();
    final List<String> notes = new ArrayList<>();
    final List<String> harnessRefusals = new ArrayList<>();
    final List<ChargeCall> charges = new ArrayList<>();

    /** How often a new round cleared the last round's refusal. */
    int refusalClears;

    /** What the verdict decision reads; one blocker makes it Request changes. */
    int openBlockers;

    @Override
    public void appendEvent(String reviewId, String lane, String type, String detail) {
    }

    @Override
    public void appendEvent(String reviewId, String lane, String type, String detail, String threadRef) {
    }

    @Override
    public void updateStage(String reviewId, int stage) {
    }

    @Override
    public void updateStatus(String reviewId, String status, int stage) {
        statuses.add(status);
    }

    @Override
    public void clearScheduledRetry(String reviewId) {
    }

    @Override
    public void setNote(String reviewId, String note) {
        notes.add(note);
    }

    /** Step 5: the harness refusal the attention feed reads. */
    @Override
    public void setHarnessRefusal(String reviewId, String reason) {
        if (reason != null) {
            harnessRefusals.add(reason);
        } else {
            refusalClears++;
        }
    }

    @Override
    public void recordOutcome(String reviewId, ReviewResult result, int stage) {
    }

    @Override
    public Optional<PriorRun> priorRunFor(String reviewId) {
        return Optional.empty();
    }

    @Override
    public void recordReconciliation(String reviewId, List<FindingVerdict> verdicts, List<PriorFinding> priorFindings) {
    }

    @Override
    public void recordOpenFindings(String reviewId, ReviewResult result, List<FindingVerdict> verdicts,
                                   List<PriorFinding> priorFindings) {
    }

    @Override
    public void touch(String reviewId) {
    }

    @Override
    public void recordCharges(ChargeCall call) {
        charges.add(call);
    }

    @Override
    public void recordPosted(String reviewId, String commit, String summaryCommentId) {
    }

    @Override
    public int openBlockers(String reviewId) {
        return openBlockers;
    }

    @Override
    public void recordPostedVerdict(String reviewId, String commit, ReviewVerdict verdict, String reviewRef,
                                    String reviewUrl) {
    }
}
