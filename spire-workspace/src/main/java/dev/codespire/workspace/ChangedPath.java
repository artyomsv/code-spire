package dev.codespire.workspace;

import java.util.Objects;

/**
 * One path the agent touched, how, and whether the entry is a SYMLINK.
 *
 * <p>The mode is here because the push gate cannot judge it any other way. A glob matches a
 * PATH, and a symlink committed at {@code .github} pointing to {@code payload/} appears in the
 * diff as the path {@code .github} — which no floor glob matches, since they all carry the
 * {@code .github/workflows/} prefix. The link is what redirects the read, so the fact that it
 * IS a link has to travel with the path.
 */
public record ChangedPath(String path, ChangeKind kind, boolean symlink) {

    public ChangedPath {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
    }

    /**
     * An ordinary file or directory entry.
     *
     * <p>For tests and for callers that genuinely have no mode. <b>Production must not use
     * this</b> — a default that silently means "not a link" is how a new component gets
     * dropped at a rebuild site, which this milestone has already paid for once. The one
     * production builder is {@code PublishRepo.safe}, and it takes the mode.
     */
    public ChangedPath(String path, ChangeKind kind) {
        this(path, kind, false);
    }
}
