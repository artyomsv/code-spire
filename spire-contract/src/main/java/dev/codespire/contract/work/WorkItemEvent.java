package dev.codespire.contract.work;

import dev.codespire.worksource.WorkIssueLocation;
import java.util.UUID;
import java.util.Map;

/** Only workflow control facts. A tracker ticket is deliberately not a component. */
public record WorkItemEvent(String workItemId, UUID sourceId, UUID repositoryId, WorkIssueLocation issue,
                            long generation, long policyRevision, WorkPolicy.Profile admittedProfile, Map<WorkPolicy.Phase,String> admittedModes,
                            Authority authority, WorkPolicy.Selection policy, String phase, String workflowStatus, String reason,
                            WorkPolicyLimits admittedLimits, String milestone, WorkGate gate, WorkProgress progress,
                            WorkPreparation preparation, WorkControl control, WorkDraft draft, WorkSteps steps) {
    /** Every event written before the plan cursor existed (M4 slice 3, A17) carried none. */
    public WorkItemEvent(String workItemId, UUID sourceId, UUID repositoryId, WorkIssueLocation issue,
                         long generation, long policyRevision, WorkPolicy.Profile admittedProfile, Map<WorkPolicy.Phase,String> admittedModes,
                         Authority authority, WorkPolicy.Selection policy, String phase, String workflowStatus, String reason,
                         WorkPolicyLimits admittedLimits, String milestone, WorkGate gate, WorkProgress progress,
                         WorkPreparation preparation, WorkControl control, WorkDraft draft) {
        this(workItemId,sourceId,repositoryId,issue,generation,policyRevision,admittedProfile,admittedModes,authority,policy,phase,workflowStatus,reason,admittedLimits,milestone,gate,progress,preparation,control,draft,null);
    }
    public WorkItemEvent(String workItemId, UUID sourceId, UUID repositoryId, WorkIssueLocation issue,
                         long generation, long policyRevision, WorkPolicy.Profile admittedProfile, Map<WorkPolicy.Phase,String> admittedModes,
                         Authority authority, WorkPolicy.Selection policy, String phase, String workflowStatus, String reason,
                         WorkPolicyLimits admittedLimits, String milestone, WorkGate gate, WorkProgress progress,
                         WorkPreparation preparation, WorkControl control) {
        this(workItemId,sourceId,repositoryId,issue,generation,policyRevision,admittedProfile,admittedModes,authority,policy,phase,workflowStatus,reason,admittedLimits,milestone,gate,progress,preparation,control,null);
    }
    public WorkItemEvent(String workItemId, UUID sourceId, UUID repositoryId, WorkIssueLocation issue,
                         long generation, long policyRevision, WorkPolicy.Profile admittedProfile, Map<WorkPolicy.Phase,String> admittedModes,
                         Authority authority, WorkPolicy.Selection policy, String phase, String workflowStatus, String reason,
                         WorkPolicyLimits admittedLimits, String milestone, WorkGate gate, WorkProgress progress,WorkPreparation preparation) {
        this(workItemId,sourceId,repositoryId,issue,generation,policyRevision,admittedProfile,admittedModes,authority,policy,phase,workflowStatus,reason,admittedLimits,milestone,gate,progress,preparation,null,null);
    }
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
                admittedModes, authority, policy, phase, workflowStatus, reason, admittedLimits, value, gate, progress,preparation,control,draft,steps);
    }

    public WorkItemEvent decision(long revision, Authority observed, WorkPolicy.Selection selection, String nextPhase,
                                  String status, String why, String event, WorkGate nextGate, WorkProgress nextProgress) {
        return new WorkItemEvent(workItemId,sourceId,repositoryId,issue,generation,revision,admittedProfile,admittedModes,
                observed,selection,nextPhase,status,why,admittedLimits,event,nextGate,nextProgress,preparation,control,draft,steps);
    }
    /**
     * Carries the draft unchanged; what readmission keeps of it is slice 11's decision. The plan cursor and the step count
     * start again (decision 12), so no build of the next generation reads this one's cursor; runs and spend accumulate.
     */
    public WorkItemEvent readmit() {
        return new WorkItemEvent(workItemId,sourceId,repositoryId,issue,generation+1,policyRevision,policy.selected(),policy.effective(),
                authority,policy,"intake","awaiting_input","readmitted",policy.limits(),"READMITTED",null,progress.reserve(false).withSteps(0),preparation,control,draft,steps==null?null:steps.restarted());
    }
    public WorkItemEvent controlled(WorkControl value) {
        return new WorkItemEvent(workItemId,sourceId,repositoryId,issue,generation,policyRevision,admittedProfile,admittedModes,
                authority,policy,phase,workflowStatus,reason,admittedLimits,milestone,gate,progress,preparation,value,draft,steps);
    }
    public WorkItemEvent prepared(WorkPreparation value) {
        return new WorkItemEvent(workItemId,sourceId,repositoryId,issue,generation,policyRevision,admittedProfile,admittedModes,
                authority,policy,phase,workflowStatus,reason,admittedLimits,"ARTIFACTS_REGISTERED",gate,progress,value,control,draft,steps);
    }
    /** The generated item's draft (M4 slice 3, A4); the milestone is the decision's that records it. */
    public WorkItemEvent drafted(WorkDraft value) {
        return new WorkItemEvent(workItemId,sourceId,repositoryId,issue,generation,policyRevision,admittedProfile,admittedModes,
                authority,policy,phase,workflowStatus,reason,admittedLimits,milestone,gate,progress,preparation,control,value,steps);
    }
    /** The plan cursor of a binding-6 build (M4 slice 3, A17); the milestone is the decision's that moves it. */
    public WorkItemEvent stepped(WorkSteps value) {
        return new WorkItemEvent(workItemId,sourceId,repositoryId,issue,generation,policyRevision,admittedProfile,admittedModes,
                authority,policy,phase,workflowStatus,reason,admittedLimits,milestone,gate,progress,preparation,control,draft,value);
    }
}
