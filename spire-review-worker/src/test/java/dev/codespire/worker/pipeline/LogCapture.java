package dev.codespire.worker.pipeline;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

/**
 * Every record one class's logger writes while open: its message, its parameters and its whole throwable chain, as one
 * line each. JBoss Logging reaches the JUL logger of the same name (as in the orchestrator's LlmKeyCheckTest).
 */
final class LogCapture extends Handler implements AutoCloseable {

    private final List<String> lines = new ArrayList<>();
    private final Logger logger;

    private LogCapture(Class<?> type) {
        this.logger = Logger.getLogger(type.getName());
        setLevel(Level.ALL);
        logger.addHandler(this);
    }

    static LogCapture of(Class<?> type) {
        return new LogCapture(type);
    }

    List<String> lines() {
        return List.copyOf(lines);
    }

    @Override
    public void publish(LogRecord record) {
        StringWriter trace = new StringWriter();
        if (record.getThrown() != null) {
            record.getThrown().printStackTrace(new PrintWriter(trace));
        }
        lines.add(record.getMessage() + Arrays.toString(record.getParameters()) + trace);
    }

    @Override
    public void flush() {
    }

    @Override
    public void close() {
        logger.removeHandler(this);
    }
}
