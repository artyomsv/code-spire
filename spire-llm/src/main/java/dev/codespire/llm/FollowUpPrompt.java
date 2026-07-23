package dev.codespire.llm;

import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.llm.PromptCatalog;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.contract.scm.ThreadMessage;
import dev.codespire.contract.scm.ThreadTranscript;

import java.util.HashMap;
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
        return render(thread, diffText, null);
    }

    public static Prompt render(ThreadTranscript thread, String diffText, PromptTemplate template) {
        PromptTemplate effective = template != null
                ? template : PromptCatalog.defaultTemplate(PromptKind.FOLLOWUP);
        Map<String, String> values = new HashMap<>();
        values.put("anchor", thread.path() + " line " + thread.line() + " (commit " + thread.commit() + ")");
        values.put("diff", diffText == null ? "" : diffText);
        values.put("thread", renderThread(thread));
        return PromptRenderer.render(effective, values).prompt();
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
