package dev.codespire.runtime;

import java.util.Map;

/**
 * Files a run unit's init container writes into the {@code volume} mounted at {@code path}, for the agent to read
 * read-only (harness reviews, step 6). The publisher never sees them.
 */
public record UnitInputs(String volume, String path, Map<String, String> files) {

    /** The runtime's own bound on the total UTF-8 bytes of every file's text. */
    public static final int MAX_BYTES = 1024 * 1024;
}
