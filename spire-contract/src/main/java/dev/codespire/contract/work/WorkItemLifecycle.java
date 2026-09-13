package dev.codespire.contract.work;

import dev.codespire.worksource.WorkIssueLocation;
import java.util.List;
import java.util.UUID;

/** Sole work domain-event decider. Executors never manufacture phase completion. */
public final class WorkItemLifecycle {
    private WorkItemLifecycle() {}

    public static WorkItemEvent reconcile(String id, UUID source, UUID repository, WorkIssueLocation issue,
                                          long policyRevision, WorkItemEvent.Authority authority, WorkPolicy.Selection policy, WorkItemEvent previous) {
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
                        : policy.effective(), authority, policy, phase, status, reason);
    }

    public static WorkItemEvent fold(List<WorkItemEvent> events) {
        return events.isEmpty() ? null : events.getLast();
    }
}
