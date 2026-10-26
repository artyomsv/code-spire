package dev.codespire.contract.work;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/**
 * The plan cursor of a binding-6 build (M4 slice 3, §6.6, A17): how many steps the approved plan has, which one is next,
 * and what every completed step left behind. The decider moves it; nothing else does.
 *
 * @param index the next step to build, 0-based; {@code total} once every step completed
 * @param lost the runs whose held checkpoint a step start found gone (decision 11), so no retry starts from them again
 */
public record WorkSteps(int total, int index, List<StepResult> completed, Set<String> lost) {
    /** More steps than a plan prompt may ask for (§6.4) and than any profile's step limit allows today. */
    public static final int MAX_STEPS = 50;

    /**
     * One completed step. {@code execution} is the step's run, binding and head, verified PASSED unless the step was
     * empty (decision 9, A10). {@code holdsBundle} is false only for an empty step that started from the base: its run's
     * handoff holds nothing a later step could start from.
     */
    public record StepResult(int index, WorkExecution execution, boolean empty, boolean holdsBundle) {
        public StepResult {
            if (index < 0) throw new IllegalArgumentException("A step index is 0-based, so " + index + " names none");
            Objects.requireNonNull(execution, "A completed step names its run");
            if (!empty && (execution.verification() == null || !execution.verification().passed()))
                throw new IllegalArgumentException("A step that changed something completes only on a passed verification");
            if (!empty && !holdsBundle) throw new IllegalArgumentException("A step that committed holds its bundle");
        }
    }

    public WorkSteps {
        if (total < 1 || total > MAX_STEPS) throw new IllegalArgumentException("A plan has 1 to " + MAX_STEPS + " steps, not " + total);
        if (index < 0 || index > total) throw new IllegalArgumentException("The next step " + index + " is outside a plan of " + total);
        completed = List.copyOf(Objects.requireNonNull(completed, "completed"));
        lost = Set.copyOf(Objects.requireNonNull(lost, "lost"));
        if (completed.size() != index) throw new IllegalArgumentException("Step " + index + " is next, so exactly " + index + " steps completed");
        for (int i = 0; i < completed.size(); i++)
            if (false && completed.get(i).index() != i) throw new IllegalArgumentException("Completed steps run 0, 1, 2 …; found " + completed.get(i).index() + " at " + i);
    }

    public static WorkSteps of(int total) {
        return new WorkSteps(total, 0, List.of(), Set.of());
    }

    /** The current step completed; the cursor moves to the next one. */
    public WorkSteps completedWith(StepResult result) {
        if (done()) throw new IllegalStateException("Every step of this plan completed already");
        if (result.index() != index) throw new IllegalArgumentException("Step " + index + " is next, not step " + result.index());
        Optional<StepResult> before = lastNonEmpty();
        if (result.empty() && before.isPresent() && !before.orElseThrow().execution().head().equals(result.execution().head()))
            throw new IllegalArgumentException("An empty step leaves the head it started from");
        List<StepResult> next = new ArrayList<>(completed);
        next.add(result);
        return new WorkSteps(total, index + 1, next, lost);
    }

    /** A readmitted item builds the plan again from step 1 (decision 12). */
    public WorkSteps restarted() {
        return new WorkSteps(total, 0, List.of(), Set.of());
    }

    /** A start found this run's checkpoint gone (decision 11). */
    public WorkSteps lostCheckpoint(String runId) {
        Set<String> next = new HashSet<>(lost);
        next.add(Objects.requireNonNull(runId, "runId"));
        return new WorkSteps(total, index, completed, next);
    }

    public Optional<StepResult> previous() {
        return completed.isEmpty() ? Optional.empty() : Optional.of(completed.getLast());
    }

    public Optional<StepResult> lastNonEmpty() {
        return completed.reversed().stream().filter(step -> !step.empty()).findFirst();
    }

    /** The newest completed step whose run holds a bundle: the next step starts from it, and deliver publishes it (decision 9). */
    public Optional<StepResult> chainRun() {
        return completed.reversed().stream().filter(StepResult::holdsBundle).findFirst();
    }

    /** The head the next step starts from: the last change made, or the base while nothing was. */
    public String startHead(String baseCommit) {
        return lastNonEmpty().map(step -> step.execution().head()).orElse(baseCommit);
    }

    public boolean done() {
        return index == total;
    }
}
