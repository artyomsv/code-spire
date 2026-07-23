package dev.codespire.contract.llm;

/**
 * An operator-editable prompt: the {@code system} instructions (persona) and the {@code body}
 * (a template with {@code {{variable}}} placeholders). The engine appends the locked security
 * clause + output contract to the system message and fences untrusted variables in the body, so
 * neither the injection boundary nor the output contract can be edited away.
 */
public record PromptTemplate(PromptKind kind, String system, String body) {

    public PromptTemplate {
        if (kind == null) {
            throw new IllegalArgumentException("PromptTemplate.kind is required");
        }
        system = system == null ? "" : system;
        body = body == null ? "" : body;
    }
}
