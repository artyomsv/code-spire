package dev.codespire.llm;

import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.llm.PromptCatalog;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.contract.llm.PromptVariable;
import dev.codespire.diff.TokenBudget;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes {@code {{variable}}} placeholders in an operator body, fencing + sentinel-neutralizing
 * untrusted values and appending the locked security clause + output contract to the system message.
 * The single rendering path for both the built-in default and operator-customized templates.
 */
public final class PromptRenderer {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*}}");

    /** What every non-empty fenced value is wrapped in; an empty value renders as nothing, fence included. */
    static final String FENCE_OPEN = "BEGIN_UNTRUSTED_DATA\n";
    static final String FENCE_CLOSE = "\nEND_UNTRUSTED_DATA";

    /** A fenced variable and the token budget it is clipped to in this render. */
    private record Slot(PromptVariable variable, int budget) {
    }

    private PromptRenderer() {
    }

    /**
     * The rendered prompt plus the names of the fenced values that had to be clipped to their budget.
     *
     * <p>Names rather than one flag: a clipped {@code context} makes a review partial, but only a clipped
     * {@code diff} means the model did not see some changed files, and the posted note names those.
     */
    public record Rendered(Prompt prompt, Set<String> clippedVariables) {

        public Rendered {
            clippedVariables = Set.copyOf(clippedVariables);
        }

        /** Whether any fenced value was clipped. */
        public boolean truncated() {
            return !clippedVariables.isEmpty();
        }
    }

    public static Rendered render(PromptTemplate template, Map<String, String> values) {
        return render(template, values, Map.of());
    }

    /**
     * @param budgetOverrides a token budget per variable name that replaces the palette's for this render; the
     *                        review input limit sets {@code diff} here from the model's window
     */
    public static Rendered render(PromptTemplate template, Map<String, String> values,
                                  Map<String, Integer> budgetOverrides) {
        PromptKind kind = template.kind();
        Map<String, PromptVariable> palette = new HashMap<>();
        for (PromptVariable variable : PromptCatalog.palette(kind)) {
            palette.put(variable.name(), variable);
        }

        Set<String> clipped = new LinkedHashSet<>();
        Matcher matcher = TOKEN.matcher(template.body());
        StringBuilder user = new StringBuilder();
        while (matcher.find()) {
            String name = matcher.group(1);
            PromptVariable variable = palette.get(name);
            String raw = values.getOrDefault(name, "");
            String rendered = "";
            if (variable != null) {
                Slot slot = new Slot(variable, budgetOverrides.getOrDefault(name, variable.maxTokens()));
                rendered = renderValue(slot, raw, clipped);
            }
            matcher.appendReplacement(user, Matcher.quoteReplacement(rendered));
        }
        matcher.appendTail(user);

        String system = template.system() + "\n\n" + PromptCatalog.lockedSystemSuffix(kind);
        return new Rendered(new Prompt(system, user.toString()), clipped);
    }

    /**
     * How many times the body uses a variable. A body that names {@code {{diff}}} twice sends the diff twice, so
     * the diff's share of the model's window is split between the uses.
     */
    public static int uses(PromptTemplate template, String variable) {
        Matcher matcher = TOKEN.matcher(template.body());
        int count = 0;
        while (matcher.find()) {
            if (matcher.group(1).equals(variable)) {
                count++;
            }
        }
        return count;
    }

    /**
     * The estimate of the fence around one non-empty fenced value. A prompt rendered with an empty diff has no fence
     * there, so the diff budget reserves this once per use of {@code {{diff}}}.
     */
    static int fenceTokens() {
        return TokenBudget.estimateTokens(FENCE_OPEN + FENCE_CLOSE);
    }

    private static String renderValue(Slot slot, String raw, Set<String> clipped) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        if (!slot.variable().fenced()) {
            return raw; // engine-computed literal (diff_kind)
        }
        String kept = raw;
        if (slot.budget() > 0 && TokenBudget.estimateTokens(raw) > slot.budget()) {
            clipped.add(slot.variable().name());
            kept = TokenBudget.clip(raw, slot.budget());
        }
        return FENCE_OPEN + neutralizeSentinels(kept) + FENCE_CLOSE;
    }

    /**
     * Neutralizes fence-sentinel occurrences INSIDE untrusted content so an injected value containing
     * "END_UNTRUSTED_DATA" cannot break out of the fence (security review finding M1). The dash
     * variant reads equivalently for review purposes but never matches the real sentinels.
     */
    static String neutralizeSentinels(String untrusted) {
        return untrusted == null ? "" : untrusted.replace("UNTRUSTED_DATA", "UNTRUSTED-DATA");
    }
}
