package dev.codespire.orchestrator.work;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Optional;

/**
 * The worst-case prompt any step of a plan can need (M4 slice 3, A8), checked when the plan is accepted so that no step
 * is refused at dispatch after its plan was approved. A step prompt carries the specification, the plan as numbered
 * titles, the current step's instruction and doneWhen, the summaries of earlier steps and a verify-failure section; each
 * part has its own share of 48 KiB, measured in UTF-8 bytes. The summaries and the verify section are capped where they
 * are written, so they are counted at their caps here; the other three are measured, the current step at the plan's
 * largest. 48 KiB of bytes is at most 49,152 characters, under the 65,536 a dispatch accepts.
 */
final class StepPromptBudget {
    static final int MAX_BYTES = 48 * 1024;
    static final int SPECIFICATION_BYTES = 16 * 1024, TITLES_BYTES = 4 * 1024, STEP_BYTES = 4 * 1024;
    static final int SUMMARIES_BYTES = 12 * 1024, VERIFY_BYTES = 8 * 1024;

    private StepPromptBudget() {}

    /** The bytes of the largest step prompt this plan can produce, before the commit instruction. */
    static long worstCase(String specification, List<WorkArtifacts.Step> steps) {
        return bytes(specification) + titles(steps) + largestStep(steps) + SUMMARIES_BYTES + VERIFY_BYTES;
    }

    /**
     * Why this plan's worst-case step prompt does not fit, naming the part over its share; empty when it fits. The
     * shares add up to {@link #MAX_BYTES}, so a plan whose every part fits its share fits the whole.
     */
    static Optional<String> refusal(String specification, List<WorkArtifacts.Step> steps) {
        long spec = bytes(specification), titles = titles(steps), step = largestStep(steps);
        if (spec > SPECIFICATION_BYTES) return Optional.of(over("the specification", spec, SPECIFICATION_BYTES));
        if (titles > TITLES_BYTES) return Optional.of(over("the plan titles", titles, TITLES_BYTES));
        if (step > STEP_BYTES) return Optional.of(over("the largest step's instruction and doneWhen", step, STEP_BYTES));
        return Optional.empty();
    }

    /** The plan as every step prompt shows it: "1. title" per line. */
    private static long titles(List<WorkArtifacts.Step> steps) {
        long total = 0;
        for (int i = 0; i < steps.size(); i++) total += bytes((i + 1) + ". " + steps.get(i).title() + "\n");
        return total;
    }

    private static long largestStep(List<WorkArtifacts.Step> steps) {
        return steps.stream().mapToLong(step -> bytes(step.instruction()) + bytes(step.doneWhen())).max().orElse(0);
    }

    private static String over(String part, long bytes, int share) {
        return "the worst-case step prompt does not fit: " + part + " take " + bytes + " bytes, over " + share;
    }

    private static long bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }
}
