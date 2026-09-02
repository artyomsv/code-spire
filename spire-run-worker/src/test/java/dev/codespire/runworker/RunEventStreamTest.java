package dev.codespire.runworker;

import dev.codespire.contract.event.RunEventRecord;
import dev.codespire.harness.RunEvent;
import dev.codespire.harness.UsageReport;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Turning the harness's own events into the wire stream (FR-F5).
 *
 * <p>The translation is the point: the harness vocabulary stays in its own module by ADR-034, so
 * something has to map it, and that something must not become a second place where a run's events
 * accumulate. The worker is stateless and shared by every concurrent run, and the agent writes to
 * the same stream the harness does at full access — so the volume is influenced by whatever the
 * model produced, and holding it is a denial of service on the shared worker rather than on the run
 * that caused it.
 */
class RunEventStreamTest {

    private static final String RUN_ID = "run::github:TEST-acme/app:subject-1:1";

    /** Collects what would have gone to the topic, in the order it was offered. */
    private final List<RunEventRecord> published = new ArrayList<>();

    /** A secret long enough to clear the scrub's floor, in the shape a real token has. */
    private static final String MODEL_KEY = "TEST-model-key-9876543210";

    private RunEventStream stream() {
        return new RunEventStream(RUN_ID, SecretScrub.of("TEST-bot", MODEL_KEY), published::add);
    }

    @Test
    void everyKindOfHarnessEventReachesTheStreamNamed() {
        RunEventStream stream = stream();

        stream.accept(new RunEvent.Thinking(Instant.EPOCH, "considering the diff"));
        stream.accept(new RunEvent.ToolUse(Instant.EPOCH, "bash", "./gradlew test"));
        stream.accept(new RunEvent.ToolResult(Instant.EPOCH, "bash", false, "exit 0"));
        stream.accept(new RunEvent.Output(Instant.EPOCH, "done"));
        stream.accept(new RunEvent.StateChange(Instant.EPOCH, "finishing", "wrote the bundle"));

        assertEquals(List.of("THINKING", "TOOL_USE", "TOOL_RESULT", "OUTPUT", "STATE_CHANGE"),
                published.stream().map(RunEventRecord::kind).toList(),
                "a kind the stream cannot name would reach an operator as an unlabelled row");
    }

    @Test
    void aCredentialInToolOutputNeverLeavesTheWorker() {
        // The agent runs at full access and the harness relays tool output verbatim, so a call as
        // ordinary as printenv puts the model key in a tool result. The transcript is read by a
        // viewer, unlike a failure detail, and EXECUTION-LAYER.md requires credentials redacted
        // from every event and transcript before it leaves the worker.
        RunEventStream stream = stream();

        stream.accept(new RunEvent.ToolResult(Instant.EPOCH, "bash",
                false, "OPENAI_API_KEY=" + MODEL_KEY));

        assertFalse(published.getFirst().text().contains(MODEL_KEY),
                "the model key reached the transcript: " + published.getFirst().text());
        assertTrue(published.getFirst().text().contains("OPENAI_API_KEY"),
                "the surrounding output must survive, or the line stops being diagnosable");
    }

    @Test
    void theSequenceIsDenseAndStartsAtOne() {
        // The sequence IS the transcript's order, so a gap reads as a lost event and sends someone
        // looking for a delivery failure that did not happen.
        RunEventStream stream = stream();

        for (int i = 0; i < 5; i++) {
            stream.accept(new RunEvent.Output(Instant.EPOCH, "line " + i));
        }

        assertEquals(List.of(1L, 2L, 3L, 4L, 5L),
                published.stream().map(RunEventRecord::sequence).toList());
    }

    @Test
    void aFailedToolResultCarriesItsErrorFlag() {
        RunEventStream stream = stream();

        stream.accept(new RunEvent.ToolResult(Instant.EPOCH, "bash", true, "exit 1"));
        stream.accept(new RunEvent.ToolResult(Instant.EPOCH, "bash", false, "exit 0"));

        assertTrue(published.get(0).error(), "a failed tool call must read as one without parsing text");
        assertFalse(published.get(1).error());
    }

    @Test
    void aUsageEventIsNotPartOfTheTranscript() {
        // Usage is money, and money already has a durable home in the charge ledger. Putting it in
        // a TTL'd transcript as well would make a bounded, deliberately-forgotten table the second
        // record of what a run cost, and the two would disagree the moment the TTL fired.
        RunEventStream stream = stream();

        stream.accept(new RunEvent.Usage(Instant.EPOCH, UsageReport.unknown()));

        assertEquals(List.of(), published, "usage belongs to the ledger, not to the transcript");
    }

    @Test
    void theStreamIsBoundedPerRunAndSaysWhereItStopped() {
        // The whole reason this is a stream rather than an accumulation. Past the cap the run keeps
        // working and the transcript stops growing; a reader is told, once, rather than silently
        // shown a prefix they take for the whole run.
        RunEventStream stream = stream();

        for (int i = 0; i < RunEventStream.MAX_EVENTS_PER_RUN + 50; i++) {
            stream.accept(new RunEvent.Output(Instant.EPOCH, "line " + i));
        }

        assertEquals(RunEventStream.MAX_EVENTS_PER_RUN + 1, published.size(),
                "the cap, plus exactly one notice that it was reached");
        assertEquals("TRUNCATED", published.getLast().kind());
        assertTrue(published.getLast().text().contains(String.valueOf(RunEventStream.MAX_EVENTS_PER_RUN)),
                "the notice must say how much was kept, or it tells a reader nothing actionable");
    }

    @Test
    void aPublishFailureNeverStopsTheRun() {
        // The transcript is a convenience; the run is the paid work. A broker that refuses an event
        // must not take down the run that produced it, and must not be retried into a stall either.
        List<RunEventRecord> after = new ArrayList<>();
        RunEventStream stream = new RunEventStream(RUN_ID, SecretScrub.none(), event -> {
            if (event.sequence() == 1) {
                throw new IllegalStateException("broker refused");
            }
            after.add(event);
        });

        stream.accept(new RunEvent.Output(Instant.EPOCH, "first"));
        stream.accept(new RunEvent.Output(Instant.EPOCH, "second"));

        assertEquals(1, after.size(), "the stream carries on after a refused event");
        assertEquals(2, after.getFirst().sequence(), "and does not renumber around the loss");
    }
}
