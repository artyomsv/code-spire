package dev.codespire.runworker;

import dev.codespire.contract.event.RunEventRecord;
import dev.codespire.harness.RunEvent;
import dev.codespire.workspace.SecretScrub;
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
final class RunEventStream implements Consumer<RunEvent>, RunNotes {

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

    private final SecretScrub scrub;

    RunEventStream(String runId, SecretScrub scrub, Consumer<RunEventRecord> sink) {
        this.runId = runId;
        this.scrub = scrub;
        this.sink = sink;
    }

    @Override
    public synchronized void accept(RunEvent event) {
        if (sequence >= MAX_EVENTS_PER_RUN) {
            // Checked BEFORE translating. Past the cap the run keeps working and this must cost
            // nothing per event; building and clipping a record only to discard it paid the whole
            // price of a transcript that had already stopped.
            announceTruncation();
            return;
        }
        RunEventRecord record = translate(event);
        if (record == null) {
            return;
        }
        publish(record);
    }

    /**
     * One of the worker's own lines, numbered from the same counter as the agent's.
     *
     * <p>Synchronized with {@link #accept} because the two callers are different threads: the agent's
     * events arrive on the log-reader thread while a note arrives on the control channel. Sharing the
     * counter is the point — see {@link RunNotes} for what two counters cost.
     *
     * <p>Subject to the same cap. Past it the transcript has already announced that it stops, and
     * appending after that line would contradict it; the control action still happens either way,
     * and its log line is unaffected.
     */
    @Override
    public synchronized void note(String kind, String text, boolean error) {
        if (sequence >= MAX_EVENTS_PER_RUN) {
            announceTruncation();
            return;
        }
        publish(new RunEventRecord(runId, sequence + 1, java.time.Instant.now(), kind, clip(text), error));
    }

    /**
     * The wire's own bound, applied here because a note's text is an operator's rather than an
     * agent's — the harness adapters clip their own output, which is one adapter's courtesy and not
     * a guarantee this class can rely on for text that never passed through one.
     */
    private static String clip(String text) {
        return text.length() <= RunEventRecord.MAX_TEXT_CHARS
                ? text
                : text.substring(0, RunEventRecord.MAX_TEXT_CHARS - RunEventRecord.CLIPPED.length())
                        + RunEventRecord.CLIPPED;
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
     * The run's own credentials removed before the event leaves the worker.
     *
     * <p>Applied here rather than at each translation site so no future kind can reach the sink
     * unredacted. The agent runs at full access and the harness relays tool output verbatim, so a
     * call as ordinary as {@code printenv} puts the model key in a tool result — and the transcript
     * is viewer-readable, unlike a failure detail.
     */
    private RunEventRecord redacted(RunEventRecord record) {
        String clean = scrub.clean(record.text());
        return clean.equals(record.text()) ? record
                : new RunEventRecord(record.runId(), record.sequence(), record.at(),
                        record.kind(), clean, record.error());
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
            sink.accept(redacted(record));
        } catch (RuntimeException refused) {
            LOG.warnf("run %s: event %d was not published (%s); the transcript will have a gap",
                    runId, record.sequence(), refused.getClass().getSimpleName());
        }
    }
}
