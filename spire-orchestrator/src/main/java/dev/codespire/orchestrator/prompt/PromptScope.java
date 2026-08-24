package dev.codespire.orchestrator.prompt;

import dev.codespire.contract.scm.RepoRef;

import java.util.regex.Pattern;

/**
 * A prompt override's scope: {@code "*"} for the deployment-wide default, or {@code workspace/slug}
 * for one repository.
 *
 * <p>Validated rather than trusted: the value is a primary-key component that arrives from a REST
 * path, and a scope of {@code "../../x"} would be a stored key nothing could ever address again.
 */
public final class PromptScope {

    public static final String GLOBAL = "*";

    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._\\-/]*[A-Za-z0-9])?");

    private PromptScope() {
    }

    public static String of(RepoRef repo) {
        return repo.workspace() + "/" + repo.slug();
    }

    public static String parse(String raw) {
        if (GLOBAL.equals(raw)) {
            return GLOBAL;
        }
        if (raw == null || raw.contains("..") || !raw.contains("/") || !SEGMENT.matcher(raw).matches()) {
            throw new IllegalArgumentException("Not a valid prompt scope: '" + raw + "'");
        }
        return raw;
    }
}
