package dev.codespire.contract.work;

import dev.codespire.worksource.WorkIssueLocation;
import java.util.UUID;
import java.util.Map;

/** Only workflow control facts. A tracker ticket is deliberately not a component. */
public record WorkItemEvent(String workItemId, UUID sourceId, UUID repositoryId, WorkIssueLocation issue,
                            long generation, long policyRevision, WorkPolicy.Profile admittedProfile, Map<WorkPolicy.Phase,String> admittedModes,
                            Authority authority, WorkPolicy.Selection policy, String phase, String workflowStatus, String reason,
                            WorkPolicyLimits admittedLimits, String milestone, WorkGate gate, WorkProgress progress,
                            WorkPreparation preparation) {
    public WorkItemEvent(String workItemId, UUID sourceId, UUID repositoryId, WorkIssueLocation issue,
                         long generation, long policyRevision, WorkPolicy.Profile admittedProfile, Map<WorkPolicy.Phase,String> admittedModes,
                         Authority authority, WorkPolicy.Selection policy, String phase, String workflowStatus, String reason,
                         WorkPolicyLimits admittedLimits, String milestone, WorkGate gate, WorkProgress progress) {
        this(workItemId,sourceId,repositoryId,issue,generation,policyRevision,admittedProfile,admittedModes,authority,policy,
                phase,workflowStatus,reason,admittedLimits,milestone,gate,progress,null);
    }
    public WorkItemEvent(String workItemId, UUID sourceId, UUID repositoryId, WorkIssueLocation issue,
                         long generation, long policyRevision, WorkPolicy.Profile admittedProfile, Map<WorkPolicy.Phase,String> admittedModes,
                         Authority authority, WorkPolicy.Selection policy, String phase, String workflowStatus, String reason,
                         WorkPolicyLimits admittedLimits, String milestone) {
        this(workItemId,sourceId,repositoryId,issue,generation,policyRevision,admittedProfile,admittedModes,authority,policy,
                phase,workflowStatus,reason,admittedLimits,milestone,null,WorkProgress.empty());
    }
    public WorkItemEvent(String workItemId, UUID sourceId, UUID repositoryId, WorkIssueLocation issue,
                         long generation, long policyRevision, WorkPolicy.Profile admittedProfile, Map<WorkPolicy.Phase,String> admittedModes,
                         Authority authority, WorkPolicy.Selection policy, String phase, String workflowStatus, String reason) {
        this(workItemId, sourceId, repositoryId, issue, generation, policyRevision, admittedProfile, admittedModes,
                authority, policy, phase, workflowStatus, reason, policy.limits(), "POLICY_OBSERVED");
    }
    public WorkItemEvent {
        admittedModes = Map.copyOf(admittedModes);
        admittedLimits = admittedLimits == null ? WorkPolicyLimits.inactive() : admittedLimits;
        milestone = milestone == null ? "POLICY_OBSERVED" : milestone;
        progress = progress == null ? WorkProgress.empty() : progress;
    }
    public record Authority(UUID accountId, long sourceRevision, long accountRevision, long repositoryRevision) {}

    public WorkItemEvent withMilestone(String value) {
        return new WorkItemEvent(workItemId, sourceId, repositoryId, issue, generation, policyRevision, admittedProfile,
                admittedModes, authority, policy, phase, workflowStatus, reason, admittedLimits, value, gate, progress,preparation);
    }

    public WorkItemEvent decision(long revision, Authority observed, WorkPolicy.Selection selection, String nextPhase,
                                  String status, String why, String event, WorkGate nextGate, WorkProgress nextProgress) {
        return new WorkItemEvent(workItemId,sourceId,repositoryId,issue,generation,revision,admittedProfile,admittedModes,
                observed,selection,nextPhase,status,why,admittedLimits,event,nextGate,nextProgress,preparation);
    }
    public WorkItemEvent readmit() {
        return new WorkItemEvent(workItemId,sourceId,repositoryId,issue,generation+1,policyRevision,policy.selected(),policy.effective(),
                authority,policy,"intake","awaiting_input","readmitted",policy.limits(),"READMITTED",null,progress.reserve(false),preparation);
    }
    public WorkItemEvent prepared(WorkPreparation value) {
        return new WorkItemEvent(workItemId,sourceId,repositoryId,issue,generation,policyRevision,admittedProfile,admittedModes,
                authority,policy,phase,workflowStatus,reason,admittedLimits,"ARTIFACTS_REGISTERED",gate,progress,value);
    }
}
