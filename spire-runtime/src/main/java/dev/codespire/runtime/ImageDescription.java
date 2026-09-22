package dev.codespire.runtime;

import java.util.Map;
import java.util.Objects;

/**
 * What one read of an image says about it (M3.5 part M).
 *
 * @param pinned the exact image that was read: its registry digest ({@code repo@sha256:…}) when it has
 *               one, otherwise the daemon's own image id. A tag can move and two workers can hold
 *               different images under one tag; this cannot, so a run started with it runs the image
 *               the labels came from — or fails, where the image is not there.
 * @param labels the labels of THAT image, from the same read, so the two cannot describe different images
 */
public record ImageDescription(String pinned, Map<String, String> labels) {

    public ImageDescription {
        if (pinned == null || pinned.isBlank()) throw new IllegalArgumentException("A pinned image reference is required");
        labels = labels == null ? Map.of() : Map.copyOf(Objects.requireNonNull(labels));
    }
}
