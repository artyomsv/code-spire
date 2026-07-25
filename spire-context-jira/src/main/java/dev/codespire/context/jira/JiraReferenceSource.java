package dev.codespire.context.jira;

import dev.codespire.contract.port.ContextReferenceSource;

import java.util.Locale;
import java.util.Set;

/**
 * Recognises Jira issue keys ({@code PROJ-123}) in free text.
 *
 * <p>Stateless and credential-free, so the pipeline can extract references at diff-fetch time
 * without a configured provider. Narrowing to the instance's own project keys happens later, in
 * {@link JiraContextProvider}, which is the part that needs configuration.
 */
public final class JiraReferenceSource implements ContextReferenceSource {

    @Override
    public String source() {
        return JiraContextProvider.SOURCE;
    }

    @Override
    public Set<String> referencesIn(String... texts) {
        return JiraTicketKeys.candidates(texts);
    }

    /** Issue keys compare case-insensitively. */
    @Override
    public String normalize(String reference) {
        return reference == null ? "" : reference.toUpperCase(Locale.ROOT);
    }
}
