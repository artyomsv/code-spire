package dev.codespire.llm;

import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.llm.PromptCatalog;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.scm.ThreadMessage;
import dev.codespire.contract.scm.ThreadTranscript;

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
        PromptTemplate effective = template != null
                ? template : PromptCatalog.defaultTemplate(PromptKind.RECONCILE);
        Map<String, String> values = new HashMap<>();
        values.put("prior_findings", renderFindings(findings, transcripts));
        values.put("diff", diffText == null ? "" : diffText);
        values.put("diff_kind", incremental ? INCREMENTAL_DIFF_KIND : FULL_DIFF_KIND);
        return PromptRenderer.render(effective, values).prompt();
    }

    private static String renderFindings(List<PriorFinding> findings, Map<String, ThreadTranscript> transcripts) {
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
