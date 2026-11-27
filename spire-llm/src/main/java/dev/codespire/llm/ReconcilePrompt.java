package dev.codespire.llm;

import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.llm.PromptCatalog;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.scm.ThreadMessage;
import dev.codespire.contract.scm.ThreadTranscript;
import dev.codespire.diff.TokenBudget;

import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Prompt for the reconcile call (ADR-019): prior findings + their thread
 * transcripts + the incremental diff -> one verdict per finding. Renders
 * through {@link PromptRenderer} against the built-in default template
 * unless an operator-customized one is supplied.
 */
public final class ReconcilePrompt {

    private static final String INCREMENTAL_DIFF_KIND = "Changes since the prior review (incremental diff)";
    private static final String FULL_DIFF_KIND = "Current full diff (the incremental diff is unavailable — "
            + "history was rewritten; judge each finding against the current state)";

    private ReconcilePrompt() {
    }

    public static Prompt render(List<PriorFinding> findings, Map<String, ThreadTranscript> transcripts,
                                String diffText, boolean incremental) {
        return render(findings, transcripts, diffText, incremental, null);
    }

    public static Prompt render(List<PriorFinding> findings, Map<String, ThreadTranscript> transcripts,
                                String diffText, boolean incremental, PromptTemplate template) {
        ReconcileInput input = new ReconcileInput(findings, transcripts, diffText,
                incremental ? DiffBasis.INCREMENTAL : DiffBasis.FULL);
        return build(input, template, DiffBudget.Limits.UNKNOWN).prompt();
    }

    /**
     * What a reconcile judges: the prior findings, their thread transcripts by thread ref, and the diff.
     *
     * @param diffText the incremental compare, or the rendered full diff when {@code basis} is {@link DiffBasis#FULL}
     */
    public record ReconcileInput(List<PriorFinding> findings, Map<String, ThreadTranscript> transcripts,
                                 String diffText, DiffBasis basis) {
    }

    /**
     * The built reconcile prompt and what was cut to fit it.
     *
     * @param clippedInputs the fenced inputs cut to their budget ({@code diff}, {@code prior_findings})
     * @param coverage      which files of the diff the model saw whole; a finding on any other file is never judged
     *                      from this call
     * @param size          whether more diff was sent than today's fixed budget allowed, so a timeout must not re-send
     *                      it
     */
    public record Built(Prompt prompt, Set<String> clippedInputs, DiffCoverage coverage, PromptSize size) {

        public Built {
            clippedInputs = Collections.unmodifiableSet(new LinkedHashSet<>(clippedInputs));
        }

        /** Whether any fenced input was clipped. */
        public boolean truncated() {
            return !clippedInputs.isEmpty();
        }

        /** The files of the diff the model did not see whole, for the posted note. */
        public List<String> omittedFiles() {
            return coverage.omittedFiles();
        }
    }

    /**
     * The reconcile prompt with the diff sized to the model ({@link DiffBudget}). The diff is a raw compare string,
     * so it keeps line clipping; {@link DiffCoverage} recovers which files the cut left whole.
     *
     * @param template the operator's RECONCILE template, or null for the built-in default
     * @throws DiffBudget.NoRoomForDiff when the model's window cannot hold a diff beside the rest of the prompt
     */
    public static Built build(ReconcileInput input, PromptTemplate template, DiffBudget.Limits limits) {
        PromptTemplate effective = template != null
                ? template : PromptCatalog.defaultTemplate(PromptKind.RECONCILE);
        Map<String, String> values = new HashMap<>();
        values.put("prior_findings", numberedFindings(input.findings(), input.transcripts()));
        values.put("diff_kind", input.basis() == DiffBasis.INCREMENTAL ? INCREMENTAL_DIFF_KIND : FULL_DIFF_KIND);
        Map<String, Integer> background = InputBudgets.forWindow(PromptKind.RECONCILE, limits);
        int budget = DiffBudget.forTemplate(effective, values, background, limits);
        String diff = input.diffText() == null ? "" : input.diffText();
        DiffCoverage coverage = TokenBudget.estimateTokens(diff) > budget
                ? DiffCoverage.clipped(diff, TokenBudget.clip(diff, budget))
                : DiffCoverage.whole();
        values.put(DiffBudget.DIFF, diff);
        PromptRenderer.Rendered rendered = PromptRenderer.render(effective, values,
                InputBudgets.withDiff(background, budget));
        return new Built(rendered.prompt(), rendered.clippedVariables(), coverage,
                DiffBudget.sizeOf(diff, budget, PromptKind.RECONCILE));
    }

    static String numberedFindings(List<PriorFinding> findings, Map<String, ThreadTranscript> transcripts) {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < findings.size(); i++) {
            PriorFinding f = findings.get(i);
            body.append(i + 1).append(". [").append(f.severity()).append("] ")
                    .append(f.path()).append(':').append(f.line()).append(" — ")
                    .append(f.message()).append('\n');
            appendTranscript(body, f.threadRef() == null ? null : transcripts.get(f.threadRef()));
        }
        return body.toString();
    }

    private static void appendTranscript(StringBuilder body, ThreadTranscript transcript) {
        if (transcript == null || transcript.messages().isEmpty()) {
            return;
        }
        body.append("   Thread:\n");
        for (ThreadMessage message : transcript.messages()) {
            body.append("   [").append(message.fromBot() ? "bot" : "reviewer").append("] ")
                    .append(message.text()).append('\n');
        }
    }
}
