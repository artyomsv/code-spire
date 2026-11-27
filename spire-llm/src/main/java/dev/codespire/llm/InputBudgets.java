package dev.codespire.llm;

import dev.codespire.contract.llm.PromptCatalog;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptVariable;

import java.util.HashMap;
import java.util.Map;

/**
 * The budgets of a prompt's background inputs ({@code context}, {@code code_context}, {@code prior_findings}, any
 * fenced variable but the diff), grown with the model's window (context cut design, 2026-11-27).
 *
 * <pre>
 * unknown window:  budget = palette budget
 * known window:    budget = palette budget x clamp(maxInputTokens / REFERENCE_WINDOW, 1, MAX_SCALE)
 * </pre>
 *
 * <p>The palette budgets were sized for a 200,000-token window, so a window of that size or smaller keeps them and a
 * larger one scales them, at most {@link #MAX_SCALE} times. On a 1,000,000-token window the linked context gets about
 * 20,000 tokens, the code context 30,000 and the prior findings 20,000: about 7% of the window, leaving the diff its
 * share ({@link DiffBudget} subtracts what these inputs actually take). An unbudgeted variable (0) stays unbudgeted.
 */
public final class InputBudgets {

    /** The window the palette budgets were sized for. */
    static final int REFERENCE_WINDOW = 200_000;

    /** The most a background budget grows: past this, more background crowds the diff more than it helps. */
    static final int MAX_SCALE = 5;

    private InputBudgets() {
    }

    /** A budget per background variable of the kind, to pass to the renderer beside the diff's; empty when unknown. */
    public static Map<String, Integer> forWindow(PromptKind kind, DiffBudget.Limits limits) {
        if (limits.maxInputTokens() == null || limits.maxInputTokens() <= REFERENCE_WINDOW) {
            return Map.of();
        }
        double scale = Math.min(MAX_SCALE, (double) limits.maxInputTokens() / REFERENCE_WINDOW);
        Map<String, Integer> budgets = new HashMap<>();
        for (PromptVariable variable : PromptCatalog.palette(kind)) {
            if (isBackground(variable)) {
                budgets.put(variable.name(), (int) Math.floor(variable.maxTokens() * scale));
            }
        }
        return Map.copyOf(budgets);
    }

    /** The budgets for a render: the background budgets plus the diff's. */
    static Map<String, Integer> withDiff(Map<String, Integer> background, int diffBudget) {
        Map<String, Integer> all = new HashMap<>(background);
        all.put(DiffBudget.DIFF, diffBudget);
        return Map.copyOf(all);
    }

    private static boolean isBackground(PromptVariable variable) {
        return variable.fenced() && variable.maxTokens() > 0 && !variable.name().equals(DiffBudget.DIFF);
    }
}
