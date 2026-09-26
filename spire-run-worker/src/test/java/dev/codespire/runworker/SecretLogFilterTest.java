package dev.codespire.runworker;

import dev.codespire.secrets.SecretScrub;
import org.jboss.logmanager.ExtLogRecord;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.logging.Level;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * No held run secret reaches a log line, in the message or anywhere in the exception chain (review of
 * PR #178: raw exceptions quoting a container's create request were logged at about 25 places).
 */
class SecretLogFilterTest {

    private static final String RUN = "TEST-log-filter-run";
    private static final String SECRET = "TEST-secret-0123456789";

    @AfterEach
    void forget() {
        LiveSecrets.forget(RUN);
    }

    private static void hold() {
        LiveSecrets.register(RUN, () -> SecretScrub.of(List.of(new SecretScrub.Credential(null, SECRET))));
    }

    private static ExtLogRecord record() {
        ExtLogRecord record = new ExtLogRecord(Level.SEVERE, "run %s failed: %s", ExtLogRecord.FormatStyle.PRINTF,
                SecretLogFilterTest.class.getName());
        record.setParameters(new Object[] {RUN, "env=[OPENAI_API_KEY=" + SECRET + "]"});
        record.setThrown(new IllegalStateException("create failed: " + SECRET,
                new RuntimeException("caused by " + SECRET)));
        return record;
    }

    @Test
    void aHeldSecretLeavesNeitherTheMessageNorTheExceptionChain() {
        hold();
        ExtLogRecord record = record();
        StackTraceElement[] frames = record.getThrown().getStackTrace();

        assertTrue(new SecretLogFilter().isLoggable(record), "scrubbed, never dropped");

        assertFalse(record.getFormattedMessage().contains(SECRET), record.getFormattedMessage());
        assertTrue(record.getFormattedMessage().contains("run " + RUN + " failed"), "the diagnosis survives");
        for (Throwable at = record.getThrown(); at != null; at = at.getCause()) {
            assertFalse(String.valueOf(at.getMessage()).contains(SECRET), at.getMessage());
        }
        assertTrue(record.getThrown().toString().startsWith("java.lang.IllegalStateException: create failed"),
                "the original class still names the failure");
        assertEquals(frames.length, record.getThrown().getStackTrace().length, "the trace still points where it did");
    }

    /** A formatter prints suppressed exceptions too; a secret there is still a secret in the log. */
    @Test
    void aSecretInASuppressedExceptionIsScrubbed() {
        hold();
        ExtLogRecord record = new ExtLogRecord(Level.SEVERE, "cleanup failed", ExtLogRecord.FormatStyle.NO_FORMAT,
                SecretLogFilterTest.class.getName());
        IllegalStateException outer = new IllegalStateException("harmless");
        outer.addSuppressed(new RuntimeException("close failed: " + SECRET));
        record.setThrown(outer);

        new SecretLogFilter().isLoggable(record);

        Throwable shown = record.getThrown();
        assertEquals(1, shown.getSuppressed().length, "the suppressed exception is kept, scrubbed");
        assertFalse(shown.getSuppressed()[0].getMessage().contains(SECRET), shown.getSuppressed()[0].getMessage());
        java.io.StringWriter printed = new java.io.StringWriter();
        shown.printStackTrace(new java.io.PrintWriter(printed));
        assertFalse(printed.toString().contains(SECRET), "nothing a formatter prints quotes the secret");
    }

    @Test
    void aForgottenRunIsNoLongerScrubbed() {
        hold();
        LiveSecrets.forget(RUN);
        ExtLogRecord record = record();

        new SecretLogFilter().isLoggable(record);

        assertTrue(record.getFormattedMessage().contains(SECRET), "nothing is held, so nothing is changed");
    }

    /** The filter does nothing unless the console handler names it; the name is the contract. */
    @Test
    void theConsoleHandlerUsesTheFilter() throws Exception {
        String config = Files.readString(Path.of("src/main/resources/application.yml"));
        String name = SecretLogFilter.class.getAnnotation(io.quarkus.logging.LoggingFilter.class).name();

        assertTrue(config.contains("filter: " + name), "quarkus.log.console.filter must name " + name);
    }
}
