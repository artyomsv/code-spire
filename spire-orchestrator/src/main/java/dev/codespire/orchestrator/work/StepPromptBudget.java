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
 * largest.
 *
 * <p>The shares bound what a part says, not what the prompt holds: every quoted part sits in a fence one backtick longer
 * than its longest backtick run, on both sides (A13), so a specification within its share can take three times it. The
 * whole prompt is therefore also composed as {@link StepPrompts} composes it, fences and framing included, for every
 * step, and must stay within what a dispatch accepts.
 */
final class StepPromptBudget {
    static final int MAX_BYTES = 48 * 1024;
    static final int SPECIFICATION_BYTES = 16 * 1024, TITLES_BYTES = 4 * 1024, STEP_BYTES = 4 * 1024;
    static final int SUMMARIES_BYTES = 12 * 1024, VERIFY_BYTES = 8 * 1024;
    /** The characters a dispatch accepts in a prompt, as {@code DispatchRequestParser} checks every step prompt. */
    static final int DISPATCH_CHARS = 64 * 1024;

    private StepPromptBudget() {}

    /**
     * The characters of the largest step prompt this plan can produce: the step whose fixed part is longest, fences
     * included, with the summaries and the verify section at their caps. Summaries are capped in bytes, which are never
     * fewer than characters; the verify section is capped in characters.
     */
    static long worstCase(String specification, List<WorkArtifacts.Step> steps) {
        long largest = 0;
        for (int index = 0; index < steps.size(); index++)
            largest = Math.max(largest, StepPrompts.fixedPart(specification, steps, index).length());
        return largest + SUMMARIES_BYTES + WorkVerifyHistory.MAX_PROMPT_SECTION_CHARS;
    }

    /**
     * Why this plan's worst-case step prompt does not fit, naming the part over its share or the whole prompt over what
     * a dispatch accepts; empty when it fits.
     */
    static Optional<String> refusal(String specification, List<WorkArtifacts.Step> steps) {
        long spec = bytes(specification), titles = titles(steps), step = largestStep(steps);
        if (spec > SPECIFICATION_BYTES) return Optional.of(over("the specification", spec + " bytes", SPECIFICATION_BYTES));
        if (titles > TITLES_BYTES) return Optional.of(over("the plan titles", titles + " bytes", TITLES_BYTES));
        if (step > STEP_BYTES) return Optional.of(over("the largest step's instruction and doneWhen", step + " bytes", STEP_BYTES));
        long whole = worstCase(specification, steps);
        if (whole > DISPATCH_CHARS)
            return Optional.of(over("the whole prompt with its fences", whole + " characters", DISPATCH_CHARS));
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

    private static String over(String part, String size, int limit) {
        return "the worst-case step prompt does not fit: " + part + " take " + size + ", over " + limit;
    }

    private static long bytes(String text) {
        return text == null ? 0 : text.getBytes(StandardCharsets.UTF_8).length;
    }
}
