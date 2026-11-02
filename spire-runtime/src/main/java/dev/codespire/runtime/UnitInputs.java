package dev.codespire.runtime;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.regex.Pattern;

/**
 * Files a run unit's init container writes into the {@code volume} mounted at {@code path}, for the agent to read
 * read-only (harness reviews, step 6). The publisher never sees them; {@link RunUnitSpec} holds that.
 *
 * <p>A file name is one path segment: no slash and no leading dot, so no name can be {@code ..} or reach outside the
 * volume when an arm writes it. Its {@code toString} prints names and byte counts, never text: the files can quote an
 * issue body or a prior finding.
 */
public record UnitInputs(String volume, String path, Map<String, String> files) {

    /** The runtime's own bound on the total UTF-8 bytes of every file's text. */
    public static final int MAX_BYTES = 1024 * 1024;

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");

    public UnitInputs {
        if (volume == null || volume.isBlank()) throw new IllegalArgumentException("Run inputs name their volume");
        if (path == null || !path.startsWith("/")) {
            throw new IllegalArgumentException("Run inputs are mounted at an absolute path, not " + path);
        }
        files = Collections.unmodifiableMap(new LinkedHashMap<>(Objects.requireNonNull(files, "files")));
        long total = 0;
        for (Map.Entry<String, String> file : files.entrySet()) {
            if (file.getKey() == null || !NAME.matcher(file.getKey()).matches()) {
                throw new IllegalArgumentException("A run input is named by one path segment, not " + file.getKey());
            }
            total += bytesOf(Objects.requireNonNull(file.getValue(), "text"));
        }
        if (total > MAX_BYTES) {
            throw new IllegalArgumentException("Run inputs of " + total + " bytes exceed the runtime's " + MAX_BYTES);
        }
    }

    /**
     * Who may see these inputs, checked by {@link RunUnitSpec}, the one place that sees all three containers. Init
     * mounts the volume writable at {@code path}, where an arm copies the files before it starts. The agent mounts it
     * read-only at most, so what it read is what it was handed. The publisher never mounts it, not even read-only: it
     * never needs the inputs, and the files carry text (an issue body, a prior finding) only the agent was to read. A
     * review unit's publisher is {@code spire-publish-none} with no write credential; the check is the unit's, not the
     * review's, so it holds just the same for a publisher that does hold one.
     */
    void requireReachOnlyInitAndAgent(ContainerSpec init, ContainerSpec agent, ContainerSpec publisher) {
        boolean initWrites = init.mounts().stream()
                .anyMatch(mount -> mount.volume().equals(volume) && mount.path().equals(path) && !mount.readOnly());
        if (!initWrites) {
            throw new IllegalArgumentException("the init container must mount the inputs volume \"" + volume
                    + "\" writable at " + path + ", where the runtime writes the inputs");
        }
        if (agent.mounts().stream().anyMatch(mount -> mount.volume().equals(volume) && !mount.readOnly())) {
            throw new IllegalArgumentException("the agent may mount the inputs volume \"" + volume
                    + "\" only read-only");
        }
        if (publisher.mounts().stream().anyMatch(mount -> mount.volume().equals(volume))) {
            throw new IllegalArgumentException("the publisher may not mount the inputs volume \"" + volume + "\"");
        }
    }

    /** The UTF-8 bytes of one file's text. */
    public static int bytesOf(String text) {
        return text.getBytes(StandardCharsets.UTF_8).length;
    }

    @Override
    public String toString() {
        StringJoiner printed = new StringJoiner(", ", "UnitInputs[volume=" + volume + ", path=" + path + ", files=[",
                "]]");
        for (Map.Entry<String, String> file : files.entrySet()) {
            printed.add(file.getKey() + "=" + bytesOf(file.getValue()) + " bytes");
        }
        return printed.toString();
    }
}
