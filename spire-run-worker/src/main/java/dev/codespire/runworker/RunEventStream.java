package dev.codespire.runworker;

import dev.codespire.contract.event.RunEventRecord;
import dev.codespire.harness.RunEvent;
import org.jboss.logging.Logger;

import java.util.function.Consumer;

/**
 * Translates one run's harness events into the wire stream, numbering them as it goes (FR-F5).
 *
 * <p>The harness vocabulary lives in its own module by ADR-034, so something has to map it. That
 * something must not become a second place where a run's events pile up: the worker is stateless and
 * shared by every concurrent run, and the agent writes to the same stream the harness does at full
 * access, so the volume is influenced by whatever the model produced. Holding it would be a denial
 * of service on the shared worker rather than on the run that caused it — the same reasoning that
 * turned the worker's own summary into a fold.
 *
 * <p>So this holds a counter and nothing else. One event in, one event out, no list.
 *
 * <p><b>Usage events are not part of the transcript.</b> Usage is money and money already has a
 * durable home in the charge ledger. Copying it into a TTL'd table would make something deliberately
 * forgotten the second record of what a run cost, and the two would disagree the moment the TTL
 * fired.
 */
final class RunEventStream implements Consumer<RunEvent> {

    private static final Logger LOG = Logger.getLogger(RunEventStream.class);

    /**
     * The most events one run may contribute to the transcript.
     *
     * <p>One observed agent run emitted 858 events, so this is roughly an order of magnitude of
     * headroom over a normal run rather than a limit a real run is expected to meet. Past it the run
     * keeps working and the transcript stops growing.
     */
    static final int MAX_EVENTS_PER_RUN = 10_000;

    private final String runId;

    private final Consumer<RunEventRecord> sink;

    private long sequence;

    private boolean announcedTruncation;

    RunEventStream(String runId, Consumer<RunEventRecord> sink) {
        this.runId = runId;
        this.sink = sink;
    }

    @Override
    public synchronized void accept(RunEvent event) {
        RunEventRecord record = translate(event);
        if (record == null) {
            return;
        }
        if (sequence >= MAX_EVENTS_PER_RUN) {
            announceTruncation();
            return;
        }
        publish(record);
    }

    private RunEventRecord translate(RunEvent event) {
        long next = sequence + 1;
        return switch (event) {
            case RunEvent.Thinking e -> new RunEventRecord(runId, next, e.at(), "THINKING", e.text(), false);
            case RunEvent.ToolUse e ->
                    new RunEventRecord(runId, next, e.at(), "TOOL_USE", e.tool() + ": " + e.summary(), false);
            case RunEvent.ToolResult e ->
                    new RunEventRecord(runId, next, e.at(), "TOOL_RESULT", e.tool() + ": " + e.summary(), e.error());
            case RunEvent.Output e -> new RunEventRecord(runId, next, e.at(), "OUTPUT", e.text(), false);
            case RunEvent.StateChange e ->
                    new RunEventRecord(runId, next, e.at(), "STATE_CHANGE", e.state() + ": " + e.detail(), false);
            // Money, and it has a durable home already. See the class note.
            case RunEvent.Usage ignored -> null;
        };
    }

    /** Once, at the cap, so a reader knows the transcript stops rather than the run stopping. */
    private void announceTruncation() {
        if (announcedTruncation) {
            return;
        }
        announcedTruncation = true;
        publish(new RunEventRecord(runId, sequence + 1, java.time.Instant.now(), "TRUNCATED",
                "the transcript stops here: this run passed " + MAX_EVENTS_PER_RUN
                        + " events and the rest are not recorded. The run itself is unaffected.",
                false));
    }

    /**
     * The sequence advances whether or not the sink accepted it, deliberately.
     *
     * <p>The transcript is a convenience and the run is the paid work, so a broker refusing an event
     * must never take down the run that produced it. Renumbering around the loss would be worse than
     * the gap: it would hide that something is missing, and a dense sequence is exactly what tells a
     * reader the transcript is whole.
     */
    private void publish(RunEventRecord record) {
        sequence = record.sequence();
        try {
            sink.accept(record);
        } catch (RuntimeException refused) {
            LOG.warnf("run %s: event %d was not published (%s); the transcript will have a gap",
                    runId, record.sequence(), refused.getClass().getSimpleName());
        }
    }
}
