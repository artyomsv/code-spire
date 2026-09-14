package dev.codespire.contract.work;

import dev.codespire.contract.scm.PullRequestRef;
import java.util.Objects;
import java.util.UUID;

/** Evidence attached to the aggregate's phase result; it contains references, never artifact bodies. */
public record WorkExecution(String runId,WorkRunBinding build,String head,UUID verificationAttempt,
                            PullRequestRef pullRequest,String reviewId) {
    public WorkExecution {
        if(runId==null || runId.isBlank())throw new IllegalArgumentException("A work execution needs its run");
        Objects.requireNonNull(build,"build");
        if(head==null || !head.matches("[0-9a-f]{40}"))throw new IllegalArgumentException("A work execution needs its full checkpoint head");
        if(reviewId!=null && reviewId.isBlank())throw new IllegalArgumentException("A review reference cannot be blank");
    }
    public WorkExecution verified(UUID attempt) {return new WorkExecution(runId,build,head,Objects.requireNonNull(attempt),pullRequest,reviewId);}
    public WorkExecution delivered(PullRequestRef ref) {return new WorkExecution(runId,build,head,verificationAttempt,Objects.requireNonNull(ref),reviewId);}
    public WorkExecution reviewed(String id) {return new WorkExecution(runId,build,head,verificationAttempt,pullRequest,Objects.requireNonNull(id));}
}
