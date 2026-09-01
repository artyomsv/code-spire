package dev.codespire.workspace;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Refuses a push whose branch touches a protected path (FR-F28, ADR-036).
 *
 * <p>Runs in the publisher, on its own clean clone, immediately before the push — never in the
 * agent's container and never on the agent's workspace, so the thing being judged cannot alter the
 * judge.
 *
 * <p>Matching is by {@link PathGlob} rather than the JDK's {@code glob:} PathMatcher: that one
 * works on {@link java.nio.file.Path} and so throws on filenames git permits, and takes its case
 * sensitivity from the filesystem instead of from the rule. See {@link PathGlob} for the
 * measurements.
 */
public final class PushGate {

    private PushGate() {
    }

    /**
     * @param profileGlobs paths an autonomy profile protects IN ADDITION to the floor. A profile may
     *                     narrow what the factory can touch; it can never widen it (ADR-035).
     */
    public static PushDecision decide(ChangeSet changes, List<String> profileGlobs) {
        List<PathGlob> floor = PathGlob.compileAll(ProtectedPaths.CI_FLOOR);
        List<PathGlob> profile = PathGlob.compileAll(profileGlobs);

        // Ordered by first appearance, deduplicated: a rename reports the same path once per side,
        // and an operator reading a refusal should see each path once, in the order git listed it.
        Set<String> blocked = new LinkedHashSet<>();
        for (ChangedPath changed : changes.paths()) {
            String path = changed.path();
            if (path == null || path.isBlank()) {
                continue;
            }
            if (matchesAny(floor, path) || matchesAny(profile, path)) {
                blocked.add(path);
            }
        }
        return blocked.isEmpty() ? PushDecision.allow() : PushDecision.refuse(List.copyOf(blocked));
    }

    private static boolean matchesAny(List<PathGlob> globs, String path) {
        for (PathGlob glob : globs) {
            if (glob.matches(path)) {
                return true;
            }
        }
        return false;
    }
}
