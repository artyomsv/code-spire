package dev.codespire.publisher;

import java.io.IOException;
import java.nio.file.Path;

/** RED: the read-only publisher (M4 slice 3). */
final class ReadOnlyPublisher {

    ReadOnlyPublisher(Path handoff, OutcomeWriter outcome, long pollMillis) {
    }

    int run() throws IOException, InterruptedException {
        throw new UnsupportedOperationException("RED");
    }
}
