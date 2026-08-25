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

    /**
     * Far more than a human filing a finding needs, and small enough that even every distinct
     * message {@link dev.codespire.orchestrator.readmodel.ReviewProjection#mergeMessages} could ever
     * accumulate at one anchor stays well under Kafka's 1MB default message size — the carried
     * snapshot this becomes part of ({@code open_findings_json}/{@code posted_findings_json}) is
     * command-carried on every later round via {@code GenerateReview.priorRun}, so a message with no
     * cap here would grow that command without bound on repeated {@code /finding}s at the same
     * anchor. {@code authorAllowed} defaults to true when a provider sets no allowlist, so on a
     * default deployment this is reachable by any PR commenter, not just a trusted operator.
     */
    static final int MAX_MESSAGE_LENGTH = 4_000;

    /** Truncate to {@link #MAX_MESSAGE_LENGTH}, leaving a visible marker rather than silently
     *  cutting off mid-word with no sign anything was lost. */
    private static String capMessage(String message) {
        if (message == null || message.length() <= MAX_MESSAGE_LENGTH) {
            return message;
        }
        return message.substring(0, MAX_MESSAGE_LENGTH) + "… [truncated]";
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
                ? new ParsedFinding(Severity.MINOR, capMessage(trimmed))
                : new ParsedFinding(severity, capMessage(parts.length > 1 ? parts[1] : ""));
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
