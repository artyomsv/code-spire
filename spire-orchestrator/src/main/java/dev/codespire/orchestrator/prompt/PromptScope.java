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

    /** Well beyond any real {@code workspace/slug} (GitHub caps a repo name at 100, an org at 39;
     *  GitLab's nested groups run longer but nowhere near this) — hygiene against an unbounded key,
     *  not a realistic ceiling anyone should ever hit. */
    private static final int MAX_LENGTH = 255;

    // Matches ONE path segment — no "/" in the allowed characters, since parse() validates each
    // segment produced by splitting on "/" separately. That per-segment validation is what rejects
    // "a//b" (an empty segment) and "a/./b" ("." fails the alnum-bounded shape below) without special
    // casing either: neither is a segment PromptScope.of ever produces.
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9._\\-]*[A-Za-z0-9])?");

    private PromptScope() {
    }

    public static String of(RepoRef repo) {
        return repo.workspace() + "/" + repo.slug();
    }

    public static String parse(String raw) {
        if (GLOBAL.equals(raw)) {
            return GLOBAL;
        }
        if (raw == null || raw.isEmpty() || raw.length() > MAX_LENGTH
                || raw.contains("..") || !raw.contains("/")) {
            throw new IllegalArgumentException("Not a valid prompt scope: '" + raw + "'");
        }
        for (String segment : raw.split("/", -1)) {
            if (!SEGMENT.matcher(segment).matches()) {
                throw new IllegalArgumentException("Not a valid prompt scope: '" + raw + "'");
            }
        }
        return raw;
    }
}
