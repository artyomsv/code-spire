package dev.codespire.publisher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;

/** RED: the handoff file reader (M4 slice 3). */
final class HandoffFiles {

    static final String ARTIFACT = "artifact.md";

    static final String STEP_SUMMARY = "step-summary.json";

    static final int ARTIFACT_MAX_BYTES = 32 * 1024;

    static final int STEP_SUMMARY_MAX_BYTES = 8 * 1024;

    enum Refusal { SYMLINK, NOT_REGULAR, TOO_LARGE, NOT_UTF8 }

    sealed interface Read {
        record Found(String text) implements Read {
        }

        record Absent() implements Read {
        }

        record Refused(Refusal why) implements Read {
        }
    }

    private HandoffFiles() {
    }

    static Read read(Path dir, String name, int maxBytes) throws IOException {
        throw new UnsupportedOperationException("RED");
    }

    static Read decode(InputStream in, int maxBytes) throws IOException {
        throw new UnsupportedOperationException("RED");
    }
}
