package dev.codespire.runtime;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * One container in a run unit.
 *
 * <p>{@code environment} carries injected credentials and MUST NOT be logged. That is enforced by
 * the redacting {@code toString()} below rather than asserted in prose, because a record prints
 * every component and {@code log.info("creating {}", spec)} is the obvious line to write. The
 * docker arm additionally keeps credentials out of container labels, which {@code docker inspect}
 * prints.
 *
 * <p>{@code mounts} are typed {@link Mount}s rather than path strings carrying a {@code :ro}
 * suffix — see that class for why the difference is a security one.
 */
public record ContainerSpec(String image, List<String> argv, Map<String, String> environment,
                            List<Mount> mounts) {

    public ContainerSpec {
        Objects.requireNonNull(image, "image");
        argv = List.copyOf(Objects.requireNonNull(argv, "argv"));
        environment = Map.copyOf(Objects.requireNonNull(environment, "environment"));
        mounts = List.copyOf(Objects.requireNonNull(mounts, "mounts"));
        if (image.isBlank()) {
            throw new IllegalArgumentException("a container must name an image");
        }
    }

    @Override
    public String toString() {
        return "ContainerSpec[image=" + image
                + ", argv=" + argv
                + ", environment=" + environment.keySet() + " (values redacted)"
                + ", mounts=" + mounts + "]";
    }
}
