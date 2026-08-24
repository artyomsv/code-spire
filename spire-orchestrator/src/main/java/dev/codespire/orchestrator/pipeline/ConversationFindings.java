package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ConversationFindingRefusal;
import dev.codespire.contract.event.IntegrationEvent.ManualCommandReceived;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.ThreadLocation;
import dev.codespire.contract.scm.ThreadRef;

import java.util.Locale;

/**
 * Turns a {@code /finding} command into either a finding to file or a refusal to say out loud.
 *
 * <p>Pure: no injection, no I/O. The stored thread location is passed in by the caller, which is the
 * only part that needs a database.
 */
public final class ConversationFindings {

    private ConversationFindings() {
    }

    public record ParsedFinding(Severity severity, String message) {
    }

    public sealed interface Outcome {
    }

    /**
     * @param threadRef the ref exactly as the event carried it — <b>un-normalized, and not what a
     * caller should act on</b>. On an SCM that threads by immediate parent, a command typed in a
     * reply carries THAT reply's id, so keying a finding, a confirmation or a turn count off this
     * splits one conversation across two refs (the defect V24's {@code root_ref} exists to fix).
     * This class is pure and has no database, so it cannot normalize; the caller resolves the
     * conversation root and uses that. Kept only because it says which ref was resolved against.
     */
    public record Filed(ThreadRef threadRef, String path, int line, Severity severity, String message)
            implements Outcome {
    }

    public record Refused(String replyText) implements Outcome {
    }

    /**
     * {@code "major shadows the field"} → MAJOR + the rest. A first word that is not a severity is
     * the start of the message, not an error: refusing on a typo would cost a round trip in the
     * thread for something the default handles.
     */
    public static ParsedFinding parse(String args) {
        String trimmed = args == null ? "" : args.trim();
        if (trimmed.isEmpty()) {
            return new ParsedFinding(Severity.MINOR, "");
        }
        String[] parts = trimmed.split("\\s+", 2);
        Severity severity = severityOrNull(parts[0]);
        return severity == null
                ? new ParsedFinding(Severity.MINOR, trimmed)
                : new ParsedFinding(severity, parts.length > 1 ? parts[1] : "");
    }

    private static Severity severityOrNull(String word) {
        try {
            return Severity.valueOf(word.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException notASeverity) {
            return null;
        }
    }

    /**
     * @param storedLocation where {@code review_thread} records this thread sitting, or null. Used
     * only when the event carries no location of its own — not every provider reports one on every
     * comment surface.
     */
    public static Outcome resolve(ManualCommandReceived event, ThreadLocation storedLocation) {
        ThreadLocation anchor = event.location() != null ? event.location() : storedLocation;
        if (event.threadRef() == null || anchor == null) {
            return new Refused(ConversationFindingRefusal.NO_ANCHOR_REPLY);
        }
        ParsedFinding parsed = parse(event.args());
        return new Filed(event.threadRef(), anchor.path(), anchor.line(),
                parsed.severity(), parsed.message());
    }
}
