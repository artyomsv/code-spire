package dev.codespire.workspace;

import java.util.Objects;

/** One path the agent touched, and how. */
public record ChangedPath(String path, ChangeKind kind) {

    public ChangedPath {
        Objects.requireNonNull(path, "path");
        Objects.requireNonNull(kind, "kind");
    }
}
