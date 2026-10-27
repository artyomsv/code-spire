package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkItemLifecycle;
import dev.codespire.contract.work.WorkSteps;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;

/**
 * Where a plan step's build starts and what its prompt says (M4 slice 3, §6.6, decisions 9, 11 and 14). The caller holds
 * the item lock. Starts, in order:
 * <ol>
 *   <li>this step's own failed checkpoint, when its verify did not pass, the checkpoint is readable and it was not
 *       recorded lost: a retry continues the step;</li>
 *   <li>otherwise the chain run, the newest earlier step whose run holds a bundle, but only while no run of this step
 *       reached ready: the run worker released the chain run's unit when the first one did;</li>
 *   <li>otherwise the base, when no earlier step holds a bundle (step 1, or only empty steps that started from the base).</li>
 * </ol>
 * A start from a checkpoint is required whenever a chain run exists: the step never falls back to the base (A2). With no
 * start left the step cannot be built again, and Retry is refused {@code step_checkpoint_lost}.
 */
@ApplicationScoped
public class WorkStepAssembly {
    static final String CHECKPOINT_LOST = "step_checkpoint_lost";

    @Inject WorkVerifyHistory verifyHistory;
    @Inject WorkStepSummaries summaries;

    /**
     * Where the step starts. {@code fromRunId} and {@code fromHead} are null for the base.
     *
     * @param failure the failure section of this step's last verify, or empty
     */
    public record Start(String fromRunId, String fromHead, boolean required, String failure) {}

    /** The current step's build: its index, prompt and start. */
    public record Step(int index, String prompt, Start start) {}

    /**
     * Whether this item's next build outside a land fix is a plan step: a cursor still running. A land fix after the plan
     * is no step (decision 12); its caller asks {@link #landFixPrompt} instead.
     */
    public static boolean buildsStep(WorkItemEvent item) {
        return item.steps() != null && !item.steps().done();
    }

    /** The task of a land fix after a delivered plan, from the stored artifacts: the whole plan is already built. */
    public static String landFixPrompt(WorkArtifacts.Evidence evidence) {
        return StepPrompts.landFix(evidence.specification(), evidence.steps());
    }

    /** The step this item builds next, with its prompt from the stored artifacts. */
    public Step step(Connection c, WorkItemEvent item, WorkArtifacts.Evidence evidence) throws SQLException {
        WorkSteps steps = item.steps();
        Start start = start(c, item).orElseThrow(() -> new IllegalStateException(CHECKPOINT_LOST));
        String prompt = StepPrompts.prompt(new StepPrompts.Input(evidence.specification(), evidence.steps(), steps.index(),
                summaries.list(c, item.workItemId(), item.generation()), start.failure()));
        return new Step(steps.index(), prompt, start);
    }

    /** Where the current step starts, or empty when none is left (decision 11). */
    Optional<Start> start(Connection c, WorkItemEvent item) throws SQLException {
        WorkSteps steps = item.steps();
        Optional<WorkVerifyHistory.Previous> mine = verifyHistory.lastNotPassedAtCurrentStep(c, item);
        String failure = mine.map(previous -> WorkVerifyHistory.promptSection(previous.verification())).orElse("");
        Optional<WorkSteps.StepResult> chain = steps.chainRun();
        if (mine.isPresent() && WorkVerifyHistory.checkpointReadable(mine.orElseThrow().verification()) && !steps.lost().contains(mine.orElseThrow().runId()))
            return Optional.of(new Start(mine.orElseThrow().runId(), mine.orElseThrow().head(), chain.isPresent(), failure));
        if (chain.isEmpty()) return Optional.of(new Start(null, null, false, failure));
        String chainRun = chain.orElseThrow().execution().runId();
        if (steps.lost().contains(chainRun) || verifyHistory.readyAtCurrentStep(c, item))
            return Optional.empty();
        return Optional.of(new Start(chainRun, chain.orElseThrow().execution().head(), true, ""));
    }

    /**
     * Why Retry build may not answer this item's verify gate; null when it may. Only a plan step whose start is gone is
     * refused, before anything is decided or dispatched, so the gate stays open with Stop (decision 11).
     */
    String retryRefusal(Connection c, List<EventEnvelope> history) throws SQLException {
        WorkItemEvent item = (WorkItemEvent) history.getLast().payload();
        if (WorkItemLifecycle.landFixInProgress(WorkItemTransitions.facts(history)) || !buildsStep(item)) return null;
        return start(c, item).isPresent() ? null : CHECKPOINT_LOST;
    }
}
