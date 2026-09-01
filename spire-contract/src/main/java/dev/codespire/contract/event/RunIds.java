package dev.codespire.contract.event;

import dev.codespire.contract.port.ScmType;

/**
 * Derives a run id from its own coordinates, so a restart loses nothing — the rule
 * {@link ReviewIds} already follows.
 *
 * <p><b>The platform is IN the key from day one.</b> {@code review_id} carries no provider, and that
 * is a tracked defect: one workspace name registered on two SCMs sums two unrelated subjects. A new
 * key does not inherit it.
 *
 * <p>Two departures from {@link ReviewIds}, both deliberate:
 *
 * <ul>
 *   <li>The repository splits on its LAST slash, not its first. GitLab namespaces nest, so
 *       {@code group/subgroup/project} is a real repository — and this project has already shipped
 *       two defects from assuming a flat {@code owner/repo}. The slug never contains a slash; the
 *       workspace may.</li>
 *   <li>The platform is spelled with {@link ScmType#providerType()}, the same string the provider
 *       registry stores, rather than a lowercased enum name. Inventing a second spelling is how the
 *       wire form and the stored form drift apart.</li>
 * </ul>
 */
public final class RunIds {

    private static final String PREFIX = "run::";

    private static final char SEPARATOR = ':';

    private RunIds() {
    }

    /**
     * @throws IllegalArgumentException when a component would make the id unparseable, so
     *                                  {@code parse(of(x))} always returns x
     */
    public static String of(ScmType scmType, String workspace, String slug, String subject, int attempt) {
        if (scmType == null) {
            throw new IllegalArgumentException("a run id must name its platform");
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("attempt starts at 1: " + attempt);
        }
        component(workspace, "workspace");
        component(slug, "slug");
        component(subject, "subject");
        if (slug.indexOf('/') >= 0) {
            // The repository splits on its last slash, so a slug containing one would silently
            // move part of itself into the workspace on the way back.
            throw new IllegalArgumentException("slug must not contain '/': " + slug);
        }
        return PREFIX + scmType.providerType() + SEPARATOR
                + workspace + "/" + slug + SEPARATOR + subject + SEPARATOR + attempt;
    }

    /**
     * The inverse of {@link #of}. Throws on malformed input rather than guessing — a parse miss that
     * falls back to a synthetic repository turns a bad id into silent data loss, which is the reason
     * {@link ReviewIds#parse} refuses the same way.
     */
    public static Parsed parse(String runId) {
        if (runId == null || !runId.startsWith(PREFIX)) {
            throw new IllegalArgumentException("not a run id (must start with " + PREFIX + "): " + runId);
        }
        // -1 keeps trailing empty fields, so "run::github:a/b::1" is four parts with a BLANK
        // subject and is refused below, rather than three parts that fail for the wrong reason.
        String[] parts = runId.substring(PREFIX.length()).split(String.valueOf(SEPARATOR), -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException("not a run id (expected platform:workspace/slug:subject:attempt, four parts): " + runId);
        }
        ScmType scmType = ScmType.fromProviderType(parts[0])
                .orElseThrow(() -> new IllegalArgumentException("unknown platform in run id: " + runId));

        int lastSlash = parts[1].lastIndexOf('/');
        if (lastSlash <= 0 || lastSlash == parts[1].length() - 1) {
            throw new IllegalArgumentException("not a run id (the repository must be workspace/slug with both non-empty): " + runId);
        }
        String workspace = parts[1].substring(0, lastSlash);
        String slug = parts[1].substring(lastSlash + 1);
        if (parts[2].isBlank()) {
            throw new IllegalArgumentException("not a run id (the subject is blank): " + runId);
        }
        int attempt;
        try {
            attempt = Integer.parseInt(parts[3]);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("not a run id (the attempt is not a number): " + runId, e);
        }
        if (attempt < 1) {
            throw new IllegalArgumentException("not a run id (the attempt starts at 1): " + runId);
        }
        return new Parsed(scmType, workspace, slug, parts[2], attempt);
    }

    private static void component(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("a run id needs a " + name);
        }
        if (value.indexOf(SEPARATOR) >= 0) {
            throw new IllegalArgumentException(name + " must not contain '" + SEPARATOR + "': " + value);
        }
    }

    /** The coordinates encoded in a run id — the id IS the address; nothing else is needed. */
    public record Parsed(ScmType scmType, String workspace, String slug, String subject, int attempt) {
    }
}
