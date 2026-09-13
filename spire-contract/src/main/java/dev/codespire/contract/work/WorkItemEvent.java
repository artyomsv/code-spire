package dev.codespire.contract.work;

import dev.codespire.worksource.WorkIssueLocation;
import java.util.UUID;
import java.util.Map;

/** Only workflow control facts. A tracker ticket is deliberately not a component. */
public record WorkItemEvent(String workItemId, UUID sourceId, UUID repositoryId, WorkIssueLocation issue,
                            long generation, long policyRevision, WorkPolicy.Profile admittedProfile, Map<WorkPolicy.Phase,String> admittedModes,
                            Authority authority, WorkPolicy.Selection policy, String phase, String workflowStatus, String reason) {
    public WorkItemEvent { admittedModes = Map.copyOf(admittedModes); }
    public record Authority(UUID accountId, long sourceRevision, long accountRevision, long repositoryRevision) {}
}
