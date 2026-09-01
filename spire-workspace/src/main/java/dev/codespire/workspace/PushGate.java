package dev.codespire.workspace;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Refuses a push whose branch touches a protected path (FR-F28, ADR-036).
 *
 * <p>Runs in the publisher, on its own clean clone, immediately before the push — never in the
 * agent's container and never on the agent's workspace, so the thing being judged cannot alter the
 * judge.
 *
 * <p>Matching is by {@link PathGlob} rather than the JDK's {@code glob:} PathMatcher: that one
 * works on {@link java.nio.file.Path} and so throws on filenames git permits, and takes its case
 * sensitivity from the filesystem instead of from the rule. See {@link PathGlob}.
 *
 * <p>The decision ignores {@link ChangeKind} deliberately. Every kind that touches a protected path
 * must refuse — the union is "refuse" — so branching on the kind could only ever narrow that,
 * adding a way to miss and no way to catch. The kind is still REPORTED, because an operator reading
 * a refusal needs to know whether the factory edited a workflow or deleted it.
 */
public final class PushGate {

    /**
     * Compiled once, at class initialisation.
     *
     * <p>{@link PathGlob#compile} promises "a startup failure rather than a silent gap", and
     * compiling per call did not deliver that: a malformed floor entry would have thrown on the
     * first push, inside the publisher, at the moment the gate was supposed to decide. Now a bad
     * entry cannot get past class loading.
     */
    private static final List<PathGlob> FLOOR = PathGlob.compileAll(ProtectedPaths.CI_FLOOR);

    private PushGate() {
    }

    /**
     * @param profileGlobs paths an autonomy profile protects IN ADDITION to the floor. A profile may
     *                     narrow what the factory can touch; it can never widen it (ADR-035).
     */
    public static PushDecision decide(ChangeSet changes, List<String> profileGlobs) {
        List<PathGlob> profile = PathGlob.compileAll(profileGlobs);

        // Ordered by first appearance and deduplicated by path: a run can touch one file across
        // several commits, and an operator reading a refusal should see each path once.
        Map<String, ChangedPath> blocked = new LinkedHashMap<>();
        for (ChangedPath changed : changes.paths()) {
            String path = changed.path();
            if (path == null || path.isBlank()) {
                continue;
            }
            if (matchesAny(FLOOR, path) || matchesAny(profile, path)) {
                blocked.putIfAbsent(path, changed);
            }
        }
        return blocked.isEmpty() ? PushDecision.allow() : PushDecision.refuse(List.copyOf(blocked.values()));
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
