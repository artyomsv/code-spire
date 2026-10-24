package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.work.WorkGate;
import dev.codespire.contract.work.WorkItemEvent;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * The item's history as the dashboard reads it. A resolved gate says what it approved and on which channel, so two
 * approvals of one phase never read the same; a land command (Stop land, Retry land) carries its operator and note,
 * which were recorded on the event and shown nowhere (live land acceptance, issues #62 and #66).
 */
final class WorkItemMilestones {
    /** The land commands whose operator and note belong to that event; any later event only carries them along. */
    static final Set<String> LAND_COMMANDS = Set.of("LAND_STOPPED", "LAND_RETRY_REQUESTED");

    /** Stands in for an event without a gate, so every gate field reads null. */
    private static final WorkGate EMPTY_GATE = new WorkGate(null, 0, null, null, 0, 0, 0, null, null, null, null, null, null, null, null);

    private WorkItemMilestones() {}

    static List<WorkItemResource.Milestone> of(List<EventEnvelope> events) {
        return events.stream().map(WorkItemMilestones::milestone).toList();
    }

    /** The OIDC subjects this history names: gate resolvers and land command operators, for their display names. */
    static Set<String> operatorSubjects(List<EventEnvelope> events) {
        Set<String> subjects = new LinkedHashSet<>();
        for (EventEnvelope event : events) {
            WorkItemEvent state = (WorkItemEvent) event.payload();
            if (state.gate() != null && state.gate().resolver() != null) subjects.add(state.gate().resolver());
            if (LAND_COMMANDS.contains(state.milestone()) && state.control() != null && state.control().operator() != null)
                subjects.add(state.control().operator());
        }
        return subjects;
    }

    private static WorkItemResource.Milestone milestone(EventEnvelope event) {
        WorkItemEvent state = (WorkItemEvent) event.payload();
        boolean clamp = "POLICY_CLAMPED".equals(state.milestone());
        WorkGate gate = state.gate() == null ? EMPTY_GATE : state.gate();
        boolean command = LAND_COMMANDS.contains(state.milestone()) && state.control() != null;
        return new WorkItemResource.Milestone(event.sequence(), "POLICY_OBSERVED".equals(state.milestone()) ? event.eventType() : state.milestone(),
                clamp ? state.policy().reason() : state.reason(), event.occurredAt(), state.phase(), state.workflowStatus(),
                state.progress().attemptId(), gate.id(), gate.state(), gate.resolver(), state.generation(),
                gate.artifact(), gate.channel(), command ? state.control().operator() : null, command ? state.control().note() : null);
    }
}
