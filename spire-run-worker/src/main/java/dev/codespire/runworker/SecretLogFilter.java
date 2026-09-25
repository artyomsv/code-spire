package dev.codespire.runworker;

import io.quarkus.logging.LoggingFilter;
import org.jboss.logmanager.ExtLogRecord;

import java.util.IdentityHashMap;
import java.util.Map;
import java.util.logging.Filter;
import java.util.logging.LogRecord;
import java.util.logging.SimpleFormatter;

/**
 * Scrubs every held run secret out of every log record: the message, and each message in the exception
 * chain (review of PR #178). Configured on the console handler as {@code run-secrets}.
 *
 * <p>An exception is replaced, not edited — a {@link Throwable}'s message cannot be changed — by a copy
 * that keeps the original class name in its message and the original stack frames, so the trace still
 * points where it did. Suppressed exceptions are not copied.
 */
@LoggingFilter(name = "run-secrets")
public final class SecretLogFilter implements Filter {

    /** Deep enough for any real chain; a guard against a cycle the identity map somehow missed. */
    private static final int MAX_CAUSES = 32;

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
        if (record.getThrown() != null && quotesASecret(record.getThrown())) record.setThrown(scrubbed(record.getThrown()));
        return true;
    }

    private static boolean quotesASecret(Throwable thrown) {
        int depth = 0;
        for (Throwable at = thrown; at != null && depth < MAX_CAUSES; at = at.getCause(), depth++) {
            String message = at.getMessage();
            if (message != null && !message.equals(LiveSecrets.clean(message))) return true;
        }
        return false;
    }

    private static Throwable scrubbed(Throwable thrown) {
        return copy(thrown, new IdentityHashMap<>(), 0);
    }

    private static Throwable copy(Throwable original, Map<Throwable, Boolean> seen, int depth) {
        if (original == null || depth >= MAX_CAUSES || seen.put(original, Boolean.TRUE) != null) return null;
        return new Scrubbed(original, copy(original.getCause(), seen, depth + 1));
    }

    /** A stand-in carrying the original's class name, scrubbed message and stack frames. */
    static final class Scrubbed extends RuntimeException {
        Scrubbed(Throwable original, Throwable cause) {
            super(original.getClass().getName() + ": " + LiveSecrets.clean(String.valueOf(original.getMessage())),
                    cause, false, true);
            setStackTrace(original.getStackTrace());
        }

        /** Printed as the original would be — its class and message — not as this wrapper's name. */
        @Override
        public String toString() {
            return getMessage();
        }
    }
}
