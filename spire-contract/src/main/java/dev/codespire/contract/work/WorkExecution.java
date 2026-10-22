package dev.codespire.contract.work;

import dev.codespire.contract.scm.PullRequestRef;
import java.util.Objects;
import java.util.UUID;

/**
 * Evidence attached to the aggregate's phase result; it contains references, never artifact bodies.
 *
 * <p>{@code verification} is the M4 result and is null in every execution stored before it; such an
 * execution keeps its bare {@code verificationAttempt}, which delivery no longer accepts on its own.
 * {@code landing} is the merge of this execution's pull request (M4 land) and is null until it lands.
 */
public record WorkExecution(String runId,WorkRunBinding build,String head,UUID verificationAttempt,
                            WorkVerification verification,PullRequestRef pullRequest,String reviewId,WorkLanding landing) {
    /** Every execution recorded before M4 land carried no landing. */
    public WorkExecution(String runId,WorkRunBinding build,String head,UUID verificationAttempt,WorkVerification verification,PullRequestRef pullRequest,String reviewId) {
        this(runId,build,head,verificationAttempt,verification,pullRequest,reviewId,null);
    }
    /** Every execution recorded before M4 carried an attempt id only. */
    public WorkExecution(String runId,WorkRunBinding build,String head,UUID verificationAttempt,PullRequestRef pullRequest,String reviewId) {
        this(runId,build,head,verificationAttempt,null,pullRequest,reviewId,null);
    }
    public WorkExecution {
        if(runId==null || runId.isBlank())throw new IllegalArgumentException("A work execution needs its run");
        Objects.requireNonNull(build,"build");
        if(head==null || !head.matches("[0-9a-f]{40}"))throw new IllegalArgumentException("A work execution needs its full checkpoint head");
        if(reviewId!=null && reviewId.isBlank())throw new IllegalArgumentException("A review reference cannot be blank");
        if(verification!=null && (!verification.attemptId().equals(verificationAttempt) || !verification.head().equals(head)))
            throw new IllegalArgumentException("A verification must name this execution's attempt and head");
        if(landing!=null && (pullRequest==null || landing.pullRequest().number()!=pullRequest.number()))
            throw new IllegalArgumentException("A landing must name this execution's pull request");
    }
    /** Stored history and TEST-only drivers before M4: an attempt with no outcome. Delivery refuses it. */
    public WorkExecution verified(UUID attempt) {return new WorkExecution(runId,build,head,Objects.requireNonNull(attempt),null,pullRequest,reviewId,landing);}
    public WorkExecution verified(WorkVerification result) {
        Objects.requireNonNull(result);
        if(!result.head().equals(head))throw new IllegalArgumentException("A verification of another head cannot verify this build");
        return new WorkExecution(runId,build,head,result.attemptId(),result,pullRequest,reviewId,landing);
    }
    public WorkExecution delivered(PullRequestRef ref) {return new WorkExecution(runId,build,head,verificationAttempt,verification,Objects.requireNonNull(ref),reviewId,landing);}
    public WorkExecution reviewed(String id) {return new WorkExecution(runId,build,head,verificationAttempt,verification,pullRequest,Objects.requireNonNull(id),landing);}
    public WorkExecution landed(WorkLanding value) {return new WorkExecution(runId,build,head,verificationAttempt,verification,pullRequest,reviewId,Objects.requireNonNull(value));}
}
