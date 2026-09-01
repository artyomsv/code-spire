package dev.codespire.runtime;

import java.util.Objects;

/**
 * A shared volume attached to one container of a run unit.
 *
 * <p><b>{@code readOnly} is a typed field rather than a {@code :ro} suffix on a path string</b>,
 * which is how the first draft carried it. That suffix is a security property in a stringly-typed
 * flag: it is the mechanism by which {@code /handoff} reaches the publisher without the publisher
 * being able to write to any shared volume (ADR-038), and a dropped or misspelled suffix silently
 * grants write access with nothing to notice. A boolean cannot be misspelled, and a caller that
 * forgets it has to say {@code false} out loud.
 */
public record Mount(String volume, String path, boolean readOnly) {

    public Mount {
        Objects.requireNonNull(volume, "volume");
        Objects.requireNonNull(path, "path");
        if (volume.isBlank()) {
            throw new IllegalArgumentException("a mount must name a volume");
        }
        if (!path.startsWith("/")) {
            throw new IllegalArgumentException("a mount path must be absolute, was: " + path);
        }
    }

    public static Mount readOnly(String volume, String path) {
        return new Mount(volume, path, true);
    }

    public static Mount writable(String volume, String path) {
        return new Mount(volume, path, false);
    }
}
