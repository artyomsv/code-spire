package dev.codespire.llm;

import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.llm.PromptCatalog;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.scm.ThreadMessage;
import dev.codespire.contract.scm.ThreadTranscript;
import dev.codespire.diff.TokenBudget;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

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
        return build(findings, transcripts, diffText, incremental, template, DiffBudget.Limits.UNKNOWN).prompt();
    }

    /**
     * The built reconcile prompt and what was cut to fit it.
     *
     * @param truncated    any fenced input was clipped
     * @param omittedFiles the files of the diff the model will not fully see; a finding on one of them is never
     *                     judged from this call
     * @param largePrompt  more diff was sent than today's fixed budget allowed, so a timeout must not re-send the call
     */
    public record Built(Prompt prompt, boolean truncated, List<String> omittedFiles, boolean largePrompt) {

        public Built {
            omittedFiles = List.copyOf(omittedFiles);
        }
    }

    /**
     * The reconcile prompt with the diff sized to the model ({@link DiffBudget}). The diff is a raw compare string,
     * so it keeps line clipping; {@link DiffCoverage} recovers which files the cut reached.
     *
     * @throws DiffBudget.NoRoomForDiff when the model's window cannot hold a diff beside the rest of the prompt
     */
    public static Built build(List<PriorFinding> findings, Map<String, ThreadTranscript> transcripts,
                              String diffText, boolean incremental, PromptTemplate template,
                              DiffBudget.Limits limits) {
        PromptTemplate effective = template != null
                ? template : PromptCatalog.defaultTemplate(PromptKind.RECONCILE);
        Map<String, String> values = new HashMap<>();
        values.put("prior_findings", numberedFindings(findings, transcripts));
        values.put("diff_kind", incremental ? INCREMENTAL_DIFF_KIND : FULL_DIFF_KIND);
        int budget = DiffBudget.forTemplate(effective, values, limits);
        String diff = diffText == null ? "" : diffText;
        List<String> omitted = TokenBudget.estimateTokens(diff) > budget
                ? DiffCoverage.omittedPaths(diff, TokenBudget.clip(diff, budget))
                : List.of();
        values.put(DiffBudget.DIFF, diff);
        PromptRenderer.Rendered rendered = PromptRenderer.render(effective, values, Map.of(DiffBudget.DIFF, budget));
        return new Built(rendered.prompt(), rendered.truncated(), omitted,
                DiffBudget.isLarge(diff, budget, PromptKind.RECONCILE));
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
