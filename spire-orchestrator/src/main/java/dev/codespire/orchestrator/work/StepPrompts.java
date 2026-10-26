package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.StepSummary;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The build prompt of plan step k (M4 slice 3, §6.6, A8, A13): the approved specification, the plan as numbered titles,
 * the current step, the decisions every earlier step recorded, oldest first, and a retry's failure section last. The
 * specification and plan are the STORED artifacts, never the tracker read again, so a ticket edit after approval changes
 * nothing. Every text a person or an agent wrote is quoted as data in a fence it cannot close.
 *
 * <p>The earlier decisions are cut to {@link StepPromptBudget#SUMMARIES_BYTES} in UTF-8 bytes, section included: the
 * newest steps are kept whole and the oldest go first, because the step just before this one is the likeliest to matter.
 */
final class StepPrompts {
    static final String SUMMARIES_LABEL = "Decisions recorded by earlier steps, oldest first";
    static final String CUT_NOTE = "(The decisions of earlier steps were cut to fit; the newest steps are kept whole.)\n";
    private static final String INSTRUCTIONS = """
            Implement one step of an approved plan. The approved specification and the whole plan are below for context;
            build only the current step, so the repository is still working and passes its checks when you finish. Keep
            to the decisions earlier steps recorded, or say in your step summary why you departed from them.

            """;

    private StepPrompts() {}

    /**
     * @param index the step to build, 0-based
     * @param summaries the newest summary of each step of this generation; only steps before {@code index} are used
     * @param verifySection a retry's failure section, or empty
     */
    record Input(String specification, List<WorkArtifacts.Step> steps, int index, List<WorkStepSummaries.Step> summaries, String verifySection) {}

    static String prompt(Input input) {
        WorkArtifacts.Step step = input.steps().get(input.index());
        int number = input.index() + 1;
        StringBuilder prompt = new StringBuilder(INSTRUCTIONS)
                .append(UntrustedText.fenced("The approved specification", input.specification())).append('\n')
                .append(UntrustedText.fenced("The plan", titles(input.steps()))).append('\n')
                .append("Now do step ").append(number).append(" of ").append(input.steps().size())
                .append(": its title, its instruction and when it is done are below.\n")
                .append(UntrustedText.fenced("Step " + number, current(step))).append('\n');
        if (input.index() > 0) prompt.append(summaries(input.summaries(), input.index()));
        return prompt.append(input.verifySection() == null ? "" : input.verifySection()).toString();
    }

    /** The decisions of steps 0 to {@code index - 1}, oldest first, as one fenced section of at most 12 KiB. */
    static String summaries(List<WorkStepSummaries.Step> summaries, int index) {
        Map<Integer, WorkStepSummaries.Step> byStep = new HashMap<>();
        for (WorkStepSummaries.Step step : summaries) if (step.stepIndex() < index) byStep.put(step.stepIndex(), step);
        List<String> blocks = new ArrayList<>();
        for (int step = 0; step < index; step++) blocks.add(block(step, byStep.get(step)));
        int budget = StepPromptBudget.SUMMARIES_BYTES;
        String section = section(blocks, budget);
        // The fence grows with the longest backtick run kept, so the body's share is found by measuring the whole section.
        while (bytes(section) > StepPromptBudget.SUMMARIES_BYTES) {
            budget -= bytes(section) - StepPromptBudget.SUMMARIES_BYTES;
            section = section(blocks, budget);
        }
        return section;
    }

    private static String section(List<String> blocks, int budget) {
        return UntrustedText.fenced(SUMMARIES_LABEL, newestFirst(blocks, budget - bytes(UntrustedText.fenced(SUMMARIES_LABEL, "")))) + "\n";
    }

    /** Keeps the newest blocks whole while they fit; the first that does not is cut, and every older one is dropped. */
    private static String newestFirst(List<String> blocks, int budget) {
        List<String> kept = new ArrayList<>();
        int used = 0;
        for (int i = blocks.size() - 1; i >= 0; i--) {
            String block = blocks.get(i);
            if (used + bytes(block) <= budget) {
                kept.addFirst(block);
                used += bytes(block);
                continue;
            }
            int left = budget - used - bytes(CUT_NOTE) - bytes("…\n");
            if (left > 0) kept.addFirst(truncate(block, left) + "…\n");
            if (budget - used >= bytes(CUT_NOTE)) kept.addFirst(CUT_NOTE);
            break;
        }
        return String.join("", kept);
    }

    private static String block(int step, WorkStepSummaries.Step recorded) {
        StepSummary summary = recorded == null ? null : recorded.summary();
        if (summary == null || summary.decisions().isEmpty() && summary.alternativesRejected().isEmpty() && summary.deviations().isEmpty())
            return "Step " + (step + 1) + " recorded no decisions.\n";
        StringBuilder block = new StringBuilder("Step ").append(step + 1).append(":\n");
        list(block, "Decisions", summary.decisions());
        list(block, "Alternatives rejected", summary.alternativesRejected());
        list(block, "Deviations from the plan", summary.deviations());
        return block.toString();
    }

    private static void list(StringBuilder block, String heading, List<String> items) {
        if (items.isEmpty()) return;
        block.append(heading).append(":\n");
        for (String item : items) block.append("- ").append(item).append('\n');
    }

    private static String titles(List<WorkArtifacts.Step> steps) {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < steps.size(); i++) lines.add((i + 1) + ". " + steps.get(i).title());
        return String.join("\n", lines);
    }

    private static String current(WorkArtifacts.Step step) {
        String text = step.title() + "\n\n" + step.instruction();
        return step.doneWhen() == null ? text : text + "\n\nDone when: " + step.doneWhen();
    }

    /** The longest prefix of {@code text} within {@code maxBytes} of UTF-8, cut on a code point boundary. */
    private static String truncate(String text, int maxBytes) {
        int used = 0;
        int end = 0;
        while (end < text.length()) {
            int codePoint = text.codePointAt(end);
            int size = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8).length;
            if (used + size > maxBytes) break;
            used += size;
            end += Character.charCount(codePoint);
        }
        return text.substring(0, end);
    }

    private static int bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }
}
