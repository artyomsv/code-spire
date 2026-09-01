package dev.codespire.workspace;

import java.util.List;
import java.util.Objects;

/** Everything the agent touched, as the push gate sees it. */
public record ChangeSet(List<ChangedPath> paths) {

    public ChangeSet {
        paths = List.copyOf(Objects.requireNonNull(paths, "paths"));
    }

    public boolean isEmpty() {
        return paths.isEmpty();
    }
}
