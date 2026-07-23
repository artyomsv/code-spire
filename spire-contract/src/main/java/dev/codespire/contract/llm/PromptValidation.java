package dev.codespire.contract.llm;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** Save-time validation + annotated preview for operator prompt templates. Pure, framework-free. */
public final class PromptValidation {

    private static final Pattern TOKEN = Pattern.compile("\\{\\{\\s*([a-zA-Z_][a-zA-Z0-9_]*)\\s*}}");

    private PromptValidation() {
    }

    public record PromptPreview(String system, String user) {
    }

    /** Variable names referenced by {@code {{name}}} tokens, in order of first appearance. */
    public static List<String> referencedVariables(String text) {
        List<String> names = new ArrayList<>();
        if (text == null) {
            return names;
        }
        Matcher m = TOKEN.matcher(text);
        while (m.find()) {
            if (!names.contains(m.group(1))) {
                names.add(m.group(1));
            }
        }
        return names;
    }

    /** Empty list = valid. Rejects variables in the system field, unknown variables, and missing required ones. */
    public static List<String> validate(PromptKind kind, String system, String body) {
        List<String> errors = new ArrayList<>();
        List<PromptVariable> palette = PromptCatalog.palette(kind);
        Set<String> known = palette.stream().map(PromptVariable::name).collect(Collectors.toSet());

        for (String inSystem : referencedVariables(system)) {
            errors.add("Variable {{" + inSystem + "}} is not allowed in the system instructions — "
                    + "variables may only appear in the body.");
        }
        List<String> inBody = referencedVariables(body);
        for (String name : inBody) {
            if (!known.contains(name)) {
                errors.add("Unknown variable {{" + name + "}} — allowed: "
                        + palette.stream().map(v -> "{{" + v.name() + "}}").collect(Collectors.joining(", ")));
            }
        }
        for (PromptVariable v : palette) {
            if (v.required() && !inBody.contains(v.name())) {
                errors.add("Required variable {{" + v.name() + "}} is missing from the body.");
            }
        }
        return errors;
    }

    /** The assembled prompt with variable slots annotated («name inserted here») — no fabricated data. */
    public static PromptPreview preview(PromptKind kind, String system, String body) {
        String annotatedBody = TOKEN.matcher(body == null ? "" : body)
                .replaceAll(mr -> Matcher.quoteReplacement("«" + mr.group(1) + " inserted here»"));
        String fullSystem = (system == null ? "" : system) + "\n\n" + PromptCatalog.lockedSystemSuffix(kind);
        return new PromptPreview(fullSystem, annotatedBody);
    }
}
