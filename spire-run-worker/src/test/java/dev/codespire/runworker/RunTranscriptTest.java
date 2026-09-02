package dev.codespire.runworker;

import dev.codespire.contract.event.RunEventRecord;
import io.smallrye.reactive.messaging.kafka.Record;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one writer to the run transcript.
 *
 * <p>One because SmallRye permits a single emitter per outgoing channel, and a second injection
 * fails the whole deployment at build time. The constraint points at the right shape anyway: the
 * transcript has two producers — the agent's own output and an operator's interventions — and they
 * belong behind one door.
 */
class RunTranscriptTest {

    private static final String RUN_ID = "run::github:TEST-acme/app:s:1";

    /** An emitter the test can make refuse. */
    private static final class FakeEmitter implements Emitter<Record<String, RunEventRecord>> {
        final List<RunEventRecord> sent = new ArrayList<>();
        RuntimeException refuses;

        @Override
        public CompletionStage<Void> send(Record<String, RunEventRecord> payload) {
            if (refuses != null) {
                throw refuses;
            }
            sent.add(payload.value());
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public <M extends Message<? extends Record<String, RunEventRecord>>> void send(M message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void complete() {
        }

        @Override
        public void error(Exception e) {
        }

        @Override
        public boolean isCancelled() {
            return false;
        }

        @Override
        public boolean hasRequests() {
            return true;
        }
    }

    private final FakeEmitter emitter = new FakeEmitter();

    private RunTranscript transcript() {
        RunTranscript transcript = new RunTranscript();
        transcript.events = emitter;
        return transcript;
    }

    @Test
    void aNoteReachesTheTranscriptWithItsRunAndKind() {
        transcript().note(RUN_ID, "STEERED", "prefer the smaller change", false);

        assertEquals(1, emitter.sent.size());
        RunEventRecord note = emitter.sent.getFirst();
        assertEquals(RUN_ID, note.runId());
        assertEquals("STEERED", note.kind());
        assertEquals("prefer the smaller change", note.text());
    }

    @Test
    void aBrokerThatRefusesDoesNotStopTheCallerThatAlreadyActed() {
        // A steer or a cancel has already happened by the time its note is written. Losing the line
        // is worse than nothing; throwing it back at a control channel that would then stop
        // delivering every later cancel is worse than both.
        emitter.refuses = new IllegalStateException("no subscriber");

        transcript().note(RUN_ID, "STEERED", "prefer the smaller change", false);
    }

    @Test
    void anAgentEventCarriesItsRefusalBackToTheCaller() {
        // The channel does not wait for write completion, so a broker refusal arrives at the
        // completion handler and nowhere else — the launcher's gap warning depends on seeing it.
        emitter.refuses = new IllegalStateException("no subscriber");
        List<Throwable> refusals = new ArrayList<>();

        transcript().emit(new RunEventRecord(RUN_ID, 1L, Instant.now(), "OUTPUT", "hello", false),
                (sent, refused) -> refusals.add(refused));

        assertTrue(refusals.isEmpty(),
                "a synchronous refusal is swallowed here rather than reaching a handler that expects"
                        + " an asynchronous one; the warning comes from the send that did reach the broker");
    }

    @Test
    void theWorkersOwnNotesHaveTheirOwnSequence() {
        // The launcher's fold numbers what the AGENT emitted. Sharing a counter would make a gap in
        // one look like a loss in the other.
        RunTranscript transcript = transcript();

        transcript.note(RUN_ID, "STEERED", "one", false);
        transcript.note(RUN_ID, "STEERED", "two", false);

        assertEquals(List.of(1L, 2L), emitter.sent.stream().map(RunEventRecord::sequence).toList());
    }

    @Test
    void anOverlongNoteIsClippedRatherThanRefused() {
        // The bound is the wire's, applied here because this text is an operator's rather than an
        // agent's — the reference adapter's own clipping is one adapter's courtesy.
        transcript().note(RUN_ID, "STEERED", "x".repeat(RunEventRecord.MAX_TEXT_CHARS + 500), false);

        assertEquals(RunEventRecord.MAX_TEXT_CHARS, emitter.sent.getFirst().text().length());
        assertTrue(emitter.sent.getFirst().text().endsWith(RunEventRecord.CLIPPED));
    }
}
