package dev.codespire.publisher;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.Consumer;

/**
 * Turns the agent's handoff files into outcome lines: the text when it was read, a refusal when it was not, and
 * nothing when the agent wrote none.
 *
 * <p>A file is never a reason to fail the run. Whether a missing artifact fails a specification run, or a missing
 * summary is {@code summary_missing}, is the run worker's and the orchestrator's decision; the publisher only says
 * what it found.
 */
final class HandoffReport {

    /** A read that failed below the four typed refusals, such as a permission error; named, never thrown. */
    static final String UNREADABLE = "UNREADABLE";

    private HandoffReport() {
    }

    static void artifact(Path handoff, OutcomeWriter outcome) {
        report(new HandoffFile(handoff, HandoffFiles.ARTIFACT, HandoffFiles.ARTIFACT_MAX_BYTES), outcome,
                outcome::artifact);
    }

    static void stepSummary(Path handoff, OutcomeWriter outcome) {
        report(new HandoffFile(handoff, HandoffFiles.STEP_SUMMARY, HandoffFiles.STEP_SUMMARY_MAX_BYTES), outcome,
                outcome::stepSummary);
    }

    private record HandoffFile(Path dir, String name, int maxBytes) {
    }

    private static void report(HandoffFile file, OutcomeWriter outcome, Consumer<String> found) {
        HandoffFiles.Read read;
        try {
            read = HandoffFiles.read(file.dir(), file.name(), file.maxBytes());
        } catch (IOException unreadable) {
            outcome.handoffFileRefused(file.name(), UNREADABLE);
            return;
        }
        switch (read) {
            case HandoffFiles.Read.Found text -> found.accept(text.text());
            case HandoffFiles.Read.Refused refused -> outcome.handoffFileRefused(file.name(), refused.why().name());
            case HandoffFiles.Read.Absent absent -> {
            }
        }
    }
}
