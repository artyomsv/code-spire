package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.StepSummary;
import dev.codespire.contract.work.WorkSteps;
import java.util.ArrayList;
import java.util.List;

/**
 * The steps section of a plan's pull request body (M4 slice 3, §6.6, A13): each step's number and title, "empty" where it
 * changed nothing, and the decisions, rejected alternatives and deviations its agent recorded.
 *
 * <p>The titles come from the plan and the summaries from the agent, so both are untrusted: each value is cut to one
 * line and neutralised as Markdown outside a fence ({@link UntrustedText#neutralise}), so it can neither mention anyone
 * nor render as HTML, and no value can start a heading or end the list. The whole section is capped well under the
 * forge's body limit: every title is kept, and summaries stop with a note once the cap is near.
 */
final class WorkPullRequestSteps {
    /** GitHub refuses a body over 65,536 characters; the rest of the body is a few lines. */
    static final int MAX_CHARS = 32_000;
    /** Room kept for the titles still to come and the cut note, so the titles always fit. */
    private static final int TITLE_ROOM = 160;
    private static final String CUT = "\n_Summaries cut to keep this description under the forge's limit; the dashboard shows them all._\n";

    /** One step as the body lists it; {@code summary} is null when its run recorded none. */
    record Line(String title, boolean empty, StepSummary summary) {}

    private WorkPullRequestSteps() {}

    /**
     * The lines of a finished cursor: each completed step with its plan title and its newest summary.
     *
     * @param titles the plan's steps in order; a missing one falls back to the step's number
     */
    static List<Line> lines(WorkSteps steps, List<WorkArtifacts.Step> titles, List<WorkStepSummaries.Step> summaries) {
        List<Line> lines = new ArrayList<>();
        for (WorkSteps.StepResult step : steps.completed()) {
            int index = step.index();
            String title = titles != null && index < titles.size() ? titles.get(index).title() : "Step " + (index + 1);
            StepSummary summary = summaries.stream().filter(written -> written.stepIndex() == index).findFirst()
                    .map(WorkStepSummaries.Step::summary).orElse(null);
            lines.add(new Line(title, step.empty(), summary));
        }
        return lines;
    }

    static String section(List<Line> lines) {
        StringBuilder out = new StringBuilder("**Plan steps:**\n\n");
        boolean cut = false;
        for (int n = 0; n < lines.size(); n++) {
            Line line = lines.get(n);
            out.append(n + 1).append(". ").append(text(line.title())).append(line.empty() ? " (empty)" : "").append('\n');
            String details = details(line.summary());
            int reserve = (lines.size() - n) * TITLE_ROOM + CUT.length();
            if (!cut && out.length() + details.length() + reserve <= MAX_CHARS) out.append(details);
            else cut = true;
        }
        if (cut) out.append(CUT);
        return out.toString();
    }

    private static String details(StepSummary summary) {
        if (summary == null) return "   - No decisions recorded\n";
        StringBuilder out = new StringBuilder();
        items(out, "Decided", summary.decisions());
        items(out, "Rejected", summary.alternativesRejected());
        items(out, "Deviated from the plan", summary.deviations());
        return out.isEmpty() ? "   - No decisions recorded\n" : out.toString();
    }

    private static void items(StringBuilder out, String label, List<String> values) {
        for (String value : values) out.append("   - ").append(label).append(": ").append(text(value)).append('\n');
    }

    /** One neutralised line: a newline would let agent text start a heading or leave the list. */
    private static String text(String value) {
        String line = UntrustedText.neutralise(value == null ? "" : value, UntrustedText.Markup.MARKDOWN).replaceAll("\\s+", " ").strip();
        return line.length() > StepSummary.MAX_ITEM_CHARS ? line.substring(0, StepSummary.MAX_ITEM_CHARS) + "…" : line;
    }
}
