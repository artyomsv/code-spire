package dev.codespire.llm;

import dev.codespire.contract.llm.Prompt;
import dev.codespire.contract.llm.PromptCatalog;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.contract.llm.PromptVariable;
import dev.codespire.diff.TokenBudget;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes {@code {{variable}}} placeholders in an operator body, fencing + sentinel-neutralizing
 * untrusted values and appending the locked security clause + output contract to the system message.
 * The single rendering path for both the built-in default and operator-customized templates.
 */
public final class PromptRenderer {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*}}");

    private PromptRenderer() {
    }

    /** The rendered prompt plus whether any fenced value had to be clipped to its budget. */
    public record Rendered(Prompt prompt, boolean truncated) {
    }

    public static Rendered render(PromptTemplate template, Map<String, String> values) {
        PromptKind kind = template.kind();
        Map<String, PromptVariable> palette = new HashMap<>();
        for (PromptVariable variable : PromptCatalog.palette(kind)) {
            palette.put(variable.name(), variable);
        }

        boolean[] truncated = {false};
        Matcher matcher = TOKEN.matcher(template.body());
        StringBuilder user = new StringBuilder();
        while (matcher.find()) {
            PromptVariable variable = palette.get(matcher.group(1));
            String raw = values.getOrDefault(matcher.group(1), "");
            String rendered = variable == null ? "" : renderValue(variable, raw, truncated);
            matcher.appendReplacement(user, Matcher.quoteReplacement(rendered));
        }
        matcher.appendTail(user);

        String system = template.system() + "\n\n" + PromptCatalog.lockedSystemSuffix(kind);
        return new Rendered(new Prompt(system, user.toString()), truncated[0]);
    }

    private static String renderValue(PromptVariable variable, String raw, boolean[] truncated) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        if (!variable.fenced()) {
            return raw; // engine-computed literal (diff_kind)
        }
        String clipped = raw;
        if (variable.maxTokens() > 0 && TokenBudget.estimateTokens(raw) > variable.maxTokens()) {
            truncated[0] = true;
            clipped = TokenBudget.clip(raw, variable.maxTokens());
        }
        return "BEGIN_UNTRUSTED_DATA\n" + neutralizeSentinels(clipped) + "\nEND_UNTRUSTED_DATA";
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
