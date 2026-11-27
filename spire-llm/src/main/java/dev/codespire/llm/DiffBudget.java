package dev.codespire.llm;

import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.llm.PromptCatalog;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.contract.llm.PromptVariable;
import dev.codespire.diff.TokenBudget;

import java.util.HashMap;
import java.util.Map;

/**
 * How many estimated tokens of diff a REVIEW or RECONCILE prompt may carry (review input limit design,
 * 2026-11-26). One pure function, so the contract is in one place:
 *
 * <pre>
 * unknown window:  budget = default
 * known window:    room   = (maxInputTokens x 0.75 - others) / uses
 *                  full   = (maxInputTokens - others) / uses
 *                  budget = max(room, min(default, full))            room &lt;= 0 refuses
 * setting:         budget = min(budget, max(diffTokenCap, default))
 * </pre>
 *
 * <p>Today's fixed budget is the floor everywhere except where the model's own window cannot hold it (then the call
 * gets what the window holds, where today's call would have overflowed). The 25% margin covers the estimator:
 * {@link TokenBudget#estimateTokens} is {@code chars / 3.2} with no safety factor, and dense text has fewer characters
 * per token. {@code others} includes the fence each use of the diff is wrapped in, so a diff of exactly {@code full}
 * tokens still renders inside the window by estimate.
 */
public final class DiffBudget {

    /** The share of the model's input limit a prompt may fill by estimate. */
    static final double MARGIN = 0.75;

    static final String DIFF = "diff";

    private DiffBudget() {
    }

    /**
     * The limits the orchestrator brokers on the credential. Null means unknown (no window in the catalog) or unset
     * (no Review diff limit), and unknown means today's budgets.
     */
    public record Limits(Integer maxInputTokens, Integer diffTokenCap) {

        /** A credential packed before the limits existed, the stub, a manual model with no setting. */
        public static final Limits UNKNOWN = new Limits(null, null);
    }

    /**
     * What the budget is computed from, besides the limits.
     *
     * @param defaultBudget today's fixed budget for the kind
     * @param otherTokens   the estimate of everything in the prompt except the diff, its fences included
     * @param diffUses      how many times the body names {@code {{diff}}}; the diff is sent once per use
     */
    public record PromptShape(int defaultBudget, int otherTokens, int diffUses) {

        int uses() {
            return Math.max(1, diffUses);
        }
    }

    /** Today's fixed diff budget for the kind: the palette's (24,000 on REVIEW, 12,000 on RECONCILE and FOLLOWUP). */
    public static int defaultFor(PromptKind kind) {
        for (PromptVariable variable : PromptCatalog.palette(kind)) {
            if (variable.name().equals(DIFF)) {
                return variable.maxTokens();
            }
        }
        throw new IllegalArgumentException("Prompt kind " + kind + " has no diff variable");
    }

    /**
     * The diff budget per use of {@code {{diff}}}.
     *
     * @throws NoRoomForDiff when the model's window is known and leaves no room for a diff at all
     */
    public static int tokens(PromptShape shape, Limits limits) {
        long budget = limits.maxInputTokens() == null
                ? shape.defaultBudget()
                : fromWindow(shape, limits.maxInputTokens());
        if (limits.diffTokenCap() != null) {
            budget = Math.min(budget, Math.max(limits.diffTokenCap(), shape.defaultBudget()));
        }
        return (int) Math.min(Integer.MAX_VALUE, budget);
    }

    private static long fromWindow(PromptShape shape, int maxInputTokens) {
        long room = ((long) Math.floor(maxInputTokens * MARGIN) - shape.otherTokens()) / shape.uses();
        if (room <= 0) {
            throw new NoRoomForDiff(maxInputTokens, shape.otherTokens());
        }
        long full = ((long) maxInputTokens - shape.otherTokens()) / shape.uses();
        return Math.max(room, Math.min(shape.defaultBudget(), full));
    }

    /**
     * The estimate of everything but the diff: the prompt rendered with an empty diff (the system message, and the
     * user message with every other variable at its own clipped size), plus the fence a non-empty diff is wrapped in,
     * once per use. An empty value renders without its fence, so the render alone would leave the fences out.
     */
    public static int otherTokens(PromptTemplate template, Map<String, String> values) {
        return otherTokens(template, values, Map.of());
    }

    /** The same, with the other variables clipped to {@code backgroundBudgets} where it names them. */
    static int otherTokens(PromptTemplate template, Map<String, String> values,
                           Map<String, Integer> backgroundBudgets) {
        Map<String, String> withoutDiff = new HashMap<>(values);
        withoutDiff.put(DIFF, "");
        Prompt prompt = PromptRenderer.render(template, withoutDiff, backgroundBudgets).prompt();
        int fences = PromptRenderer.uses(template, DIFF) * PromptRenderer.fenceTokens();
        return TokenBudget.estimateTokens(prompt.system()) + TokenBudget.estimateTokens(prompt.user()) + fences;
    }

    /**
     * Whether the call sends more diff than today's fixed budget allowed. Such a call is not retried after a timeout:
     * it is non-streaming, so each attempt sends the whole prompt again. A small diff on a large window is not large,
     * and its timeout stays retryable as today.
     *
     * @param diff   the diff value handed to the renderer
     * @param budget the per-use budget the renderer clips it to
     */
    public static PromptSize sizeOf(String diff, int budget, PromptKind kind) {
        return Math.min(TokenBudget.estimateTokens(diff), budget) > defaultFor(kind)
                ? PromptSize.LARGE
                : PromptSize.WITHIN_DEFAULT;
    }

    /**
     * The diff budget for a template and the values of its other variables, each clipped to its own budget: the one
     * {@link InputBudgets#forWindow} gives for the kind and the limits, as the render does.
     */
    static int forTemplate(PromptTemplate template, Map<String, String> otherValues, Limits limits) {
        Map<String, Integer> background = InputBudgets.forWindow(template.kind(), limits);
        PromptShape shape = new PromptShape(defaultFor(template.kind()),
                otherTokens(template, otherValues, background), PromptRenderer.uses(template, DIFF));
        return tokens(shape, limits);
    }

    /** No call is made: the prompt without its diff already fills the share of the window it may use. */
    public static final class NoRoomForDiff extends IllegalStateException {

        NoRoomForDiff(int maxInputTokens, int otherTokens) {
            super("The prompt without its diff is about " + otherTokens + " tokens, which leaves no room for a diff "
                    + "within the model's input limit of " + maxInputTokens + " tokens (its context window minus "
                    + "the output cap). Choose a model with a larger context window, or lower the provider's "
                    + "max tokens.");
        }
    }
}
