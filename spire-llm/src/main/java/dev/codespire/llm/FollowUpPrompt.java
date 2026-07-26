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
 * Renders the follow-up (in-thread reply) prompt. Delegates fencing, sentinel-neutralization, and the
 * locked security clause + output contract to {@link PromptRenderer} against the built-in default
 * template unless an operator-customized one is supplied — the same pattern as {@link ReviewPromptBuilder}
 * and {@link ReconcilePrompt}.
 */
public final class FollowUpPrompt {

    private FollowUpPrompt() {
    }

    public static Prompt render(ThreadTranscript thread, String diffText) {
        return render(thread, diffText, List.of(), null);
    }

    public static Prompt render(ThreadTranscript thread, String diffText, PromptTemplate template) {
        return render(thread, diffText, List.of(), template);
    }

    /**
     * @param otherFindings the PR's other open findings, each owning its own thread. Naming them is
     * what keeps the reply on this thread's question: the diff shows every defect in the file, and
     * without this list the model has no way to know which of them are already being discussed
     * elsewhere. The REVIEW prompt has always had the equivalent exclusion list; this one did not.
     */
    public static Prompt render(ThreadTranscript thread, String diffText,
                                List<PriorFinding> otherFindings, PromptTemplate template) {
        PromptTemplate effective = template != null
                ? template : PromptCatalog.defaultTemplate(PromptKind.FOLLOWUP);
        Map<String, String> values = new HashMap<>();
        values.put("anchor", thread.path() + " line " + thread.line() + " (commit " + thread.commit() + ")");
        values.put("diff", diffText == null ? "" : diffText);
        values.put("thread", renderThread(thread));
        values.put("other_threads", renderOtherThreads(otherFindings));
        return PromptRenderer.render(effective, values).prompt();
    }

    /** Anchors only, no severities: the point is "this belongs to another thread", not to re-review it. */
    private static String renderOtherThreads(List<PriorFinding> otherFindings) {
        if (otherFindings == null || otherFindings.isEmpty()) {
            return "(none)";
        }
        StringBuilder out = new StringBuilder();
        for (PriorFinding f : otherFindings) {
            out.append("- ").append(f.path()).append(':').append(f.line())
                    .append(" — ").append(f.message()).append('\n');
        }
        return out.toString();
    }

    private static String renderThread(ThreadTranscript thread) {
        StringBuilder out = new StringBuilder();
        for (ThreadMessage m : thread.messages()) {
            out.append(m.fromBot() ? "[bot] " : "[reviewer] ")
                    .append(m.author()).append(": ").append(m.text()).append('\n');
        }
        return out.toString();
    }
}
