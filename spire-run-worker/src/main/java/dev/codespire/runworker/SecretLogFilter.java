package dev.codespire.runworker;

import io.quarkus.logging.LoggingFilter;
import org.jboss.logmanager.ExtLogRecord;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.logging.Filter;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/**
 * Scrubs every held run secret out of every log record: the message, and every exception a formatter
 * prints — the cause chain and each suppressed exception (review of PR #178). Configured on the console
 * handler as {@code run-secrets}.
 *
 * <p>An exception is replaced, not edited — a {@link Throwable}'s message cannot be changed — by a copy
 * that keeps the original class name in its message and the original stack frames, so the trace still
 * points where it did. When anything in the graph quotes a secret, the WHOLE graph is copied: a copy
 * that kept an original branch would print that branch as it was.
 */
@LoggingFilter(name = "run-secrets")
public final class SecretLogFilter implements Filter {

    /**
     * How many exceptions one graph may hold before the rest is cut off. A real one has a handful; the
     * bound exists so a pathological graph cannot stall logging, and what is cut is dropped, never
     * printed unscrubbed.
     */
    private static final int MAX_EXCEPTIONS = 64;

    @Override
    public boolean isLoggable(LogRecord record) {
        if (LiveSecrets.isEmpty()) return true;
        String message = record instanceof ExtLogRecord ext ? ext.getFormattedMessage() : new SimpleFormatter().formatMessage(record);
        String cleaned = LiveSecrets.clean(message);
        if (cleaned != null && !cleaned.equals(message)) {
            if (record instanceof ExtLogRecord ext) ext.setMessage(cleaned, ExtLogRecord.FormatStyle.NO_FORMAT);
            else record.setMessage(cleaned);
            record.setParameters(null);
        }
        Throwable thrown = record.getThrown();
        if (thrown != null && quotesASecret(thrown, new IdentityHashMap<>())) record.setThrown(copy(thrown, new Budget()));
        return true;
    }

    /**
     * Whether the graph may quote a held secret. A graph too large to scan counts as one that does: the
     * copy is bounded too, and dropping what it cannot hold is safe where trusting it is not.
     */
    private static boolean quotesASecret(Throwable thrown, Map<Throwable, Boolean> seen) {
        if (thrown == null) return false;
        if (seen.size() >= MAX_EXCEPTIONS) return true;
        if (seen.put(thrown, Boolean.TRUE) != null) return false;
        String message = thrown.getMessage();
        if (message != null && !message.equals(LiveSecrets.clean(message))) return true;
        if (quotesASecret(thrown.getCause(), seen)) return true;
        for (Throwable suppressed : thrown.getSuppressed()) if (quotesASecret(suppressed, seen)) return true;
        return false;
    }

    /** Counts every exception copied, so a cycle or a huge graph ends in a cut, not a stall. */
    private static final class Budget {
        private final Map<Throwable, Boolean> seen = new IdentityHashMap<>();

        boolean admit(Throwable thrown) {
            return seen.size() < MAX_EXCEPTIONS && seen.put(thrown, Boolean.TRUE) == null;
        }
    }

    private static Throwable copy(Throwable original, Budget budget) {
        if (original == null || !budget.admit(original)) return null;
        Scrubbed copy = new Scrubbed(original, copy(original.getCause(), budget));
        for (Throwable suppressed : original.getSuppressed()) {
            Throwable scrubbed = copy(suppressed, budget);
            if (scrubbed != null) copy.addSuppressed(scrubbed);
        }
        return copy;
    }

    /** A stand-in carrying the original's class name, scrubbed message and stack frames. */
    static final class Scrubbed extends RuntimeException {
        Scrubbed(Throwable original, Throwable cause) {
            super(original.getClass().getName() + ": " + LiveSecrets.clean(String.valueOf(original.getMessage())),
                    cause, true, true);
            setStackTrace(original.getStackTrace());
        }

        /** Printed as the original would be — its class and message — not as this wrapper's name. */
        @Override
        public String toString() {
            return getMessage();
        }
    }
}
