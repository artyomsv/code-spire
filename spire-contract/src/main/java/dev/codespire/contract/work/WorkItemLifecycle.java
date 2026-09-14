package dev.codespire.contract.work;

import dev.codespire.worksource.WorkIssueLocation;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Sole work domain-event decider. Executors never manufacture phase completion. */
public final class WorkItemLifecycle {
    private WorkItemLifecycle() {}

    public static WorkItemEvent reconcile(String id, UUID source, UUID repository, WorkIssueLocation issue,
                                          long policyRevision, WorkItemEvent.Authority authority, WorkPolicy.Selection policy, WorkItemEvent previous) {
        if(previous!=null && previous.policyRevision()==policyRevision && previous.authority().equals(authority)
                && previous.policy().equals(policy) && previous.issue().equals(issue))return previous;
        if (previous != null && (java.util.Set.of("suspended","retired").contains(previous.workflowStatus()) || previous.preparation() != null || previous.progress().attemptId() != null || previous.gate() != null || previous.generation() > 1))
            return previous.decision(policyRevision,authority,policy,previous.phase(),previous.workflowStatus(),previous.reason(),
                    "POLICY_OBSERVED",previous.gate(),previous.progress());
        String intake = policy.effective().getOrDefault(WorkPolicy.Phase.INTAKE, "off");
        String status = "not_eligible";
        String reason = policy.reason();
        String phase = "intake";
        if (policy.selected() != null && "auto".equals(intake)) {
            phase = "spec";
            String spec = policy.effective().getOrDefault(WorkPolicy.Phase.SPEC, "off");
            status = switch (spec) {
                case "auto" -> "awaiting_input";
                case "approve" -> "capability_unavailable";
                default -> "stopped";
            };
            reason = switch (spec) {
                case "auto" -> "specification_required";
                case "approve" -> "spec_approval_unavailable";
                default -> "spec_off";
            };
        } else if (policy.selected() != null && "approve".equals(intake)) {
            status = "capability_unavailable";
            reason = "intake_approval_unavailable";
        } else if (policy.selected() != null) reason = "intake_off";
        return new WorkItemEvent(id, source, repository, issue, previous == null ? 1 : previous.generation(),
                policyRevision, previous != null && previous.admittedProfile() != null ? previous.admittedProfile()
                        : policy.selected(), previous != null && previous.admittedProfile() != null ? previous.admittedModes()
                        : policy.effective(), authority, policy, phase, status, reason,
                previous != null && previous.admittedProfile() != null ? previous.admittedLimits() : policy.limits(), "POLICY_OBSERVED")
                .controlled(previous==null?null:previous.control());
    }

    public static boolean clampChanged(WorkItemEvent previous, WorkItemEvent next) {
        if (!"policy_clamped".equals(next.policy().reason())) return false;
        if (previous == null || !"policy_clamped".equals(previous.policy().reason())) return true;
        return !java.util.Objects.equals(previous.policy().selected(), next.policy().selected())
                || !java.util.Objects.equals(previous.policy().ceiling(), next.policy().ceiling())
                || !previous.policy().effective().equals(next.policy().effective())
                || !previous.policy().limits().equals(next.policy().limits());
    }

    public static WorkItemEvent fold(List<WorkItemEvent> events) {
        return events.isEmpty() ? null : events.getLast();
    }
    public static WorkItemEvent enter(WorkItemEvent item,WorkItemEvent previous,long historySize,boolean approved,java.time.Instant now,boolean available,UUID decisionId) {
        if(item.policy().selected()==null)return state(item,"not_eligible",item.policy().reason(),"WORK_ITEM_REFUSED",item.gate(),item.progress().reserve(false));
        if("complete".equals(item.phase()))return state(item,"completed","all_phases_completed","WORK_ITEM_COMPLETED",item.gate(),item.progress().reserve(false));
        String mode=item.policy().effective().getOrDefault(WorkPolicy.Phase.valueOf(item.phase().toUpperCase(Locale.ROOT)),"off");
        if("off".equals(mode))return state(item,"not_eligible",item.phase()+"_off","WORK_ITEM_REFUSED",item.gate(),item.progress().reserve(false));
        if(item.progress().usageUnknown())return state(item,"awaiting_input","run_usage_unknown","WORK_ITEM_REFUSED",item.gate(),item.progress().reserve(false));
        if(!item.progress().within(item.policy().limits(),item.phase()))return state(item,"stopped","policy_cap_reached","WORK_ITEM_REFUSED",item.gate(),item.progress().reserve(false));
        if("approve".equals(mode) && !approved) {
            long eventRevision=historySize+1+(WorkItemLifecycle.clampChanged(previous,item)?1:0);
            WorkGate gate=new WorkGate(decisionId,1,"OPEN",item.phase(),item.generation(),eventRevision,item.policyRevision(),item.authority(),WorkGate.artifactOf(item),
                    now,now.plusSeconds(item.policy().limits().gateTtlSeconds()),null,null,null,null);
            return state(item,"waiting_approval","approval_required","GATE_OPENED",gate,item.progress().reserve(true));
        }
        if("intake".equals(item.phase()))return enter(item.decision(item.policyRevision(),item.authority(),item.policy(),"spec",item.workflowStatus(),item.reason(),item.milestone(),item.gate(),item.progress()),previous,historySize,false,now,available,decisionId);
        if(item.preparation()!=null && java.util.Set.of("spec","plan").contains(item.phase()))
            return state(item,"awaiting_input",item.phase().equals("spec")?"specification_supplied":"plan_supplied","ARTIFACT_ACCEPTED",item.gate(),item.progress().reserve(false));
        if(!available)return state(item,"capability_unavailable",item.phase()+"_capability_unavailable","CAPABILITY_UNAVAILABLE",item.gate(),item.progress().reserve(false));
        return state(item,"active","phase_started","PHASE_STARTED",item.gate(),item.progress().start(decisionId,item.phase(),now));
    }

    public static WorkItemEvent state(WorkItemEvent item,String status,String reason,String milestone,WorkGate gate,WorkProgress progress) {
        return item.decision(item.policyRevision(),item.authority(),item.policy(),item.phase(),status,reason,milestone,gate,progress);
    }
}
