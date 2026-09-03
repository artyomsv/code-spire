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
 * <p>One because SmallRye permits a single emitter per outgoing channel, and a second injection fails
 * the whole deployment at build time. It sends; it does not number. Numbering belongs to the run's own
 * {@link RunEventStream} — see {@link RunNotes} for what a second counter cost.
 */
class RunTranscriptTest {

    private static final String RUN_ID = "run::github:TEST-acme/app:s:1";

    /** An emitter the test can make refuse, synchronously or asynchronously. */
    private static final class FakeEmitter implements Emitter<Record<String, RunEventRecord>> {
        final List<RunEventRecord> sent = new ArrayList<>();
        RuntimeException throwsOnSend;
        RuntimeException failsTheFuture;

        @Override
        public CompletionStage<Void> send(Record<String, RunEventRecord> payload) {
            if (throwsOnSend != null) {
                throw throwsOnSend;
            }
            sent.add(payload.value());
            return failsTheFuture == null
                    ? CompletableFuture.completedFuture(null)
                    : CompletableFuture.failedFuture(failsTheFuture);
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

    private static RunEventRecord event(long sequence) {
        return new RunEventRecord(RUN_ID, sequence, Instant.now(), "OUTPUT", "hello", false);
    }

    @Test
    void anEventReachesTheBrokerKeyedByItsRun() {
        transcript().emit(event(1L), (sent, refused) -> {
        });

        assertEquals(1, emitter.sent.size());
        assertEquals(RUN_ID, emitter.sent.getFirst().runId());
        assertEquals(1L, emitter.sent.getFirst().sequence());
    }

    @Test
    void aBrokerRefusalArrivesAtTheHandlerTheGapWarningDependsOn() {
        // The channel does not wait for write completion, so an asynchronous refusal reaches the
        // completion handler and nowhere else. This is the case the launcher's gap warning is built
        // on, and it was the one case the previous suite never exercised: the fake could only throw
        // synchronously or answer an already-completed future, so `whenComplete` never saw a cause.
        IllegalStateException refusal = new IllegalStateException("broker said no");
        emitter.failsTheFuture = refusal;
        List<Throwable> refusals = new ArrayList<>();

        transcript().emit(event(1L), (sent, refused) -> refusals.add(refused));

        assertEquals(List.of(refusal), refusals);
    }

    @Test
    void aSynchronousRefusalIsLoggedRatherThanHandedToTheHandler() {
        // Named for what it asserts. The previous name claimed the refusal was carried back to the
        // caller while the body asserted the opposite, which invites a future reader to "fix" the
        // production code until the name comes true.
        emitter.throwsOnSend = new IllegalStateException("no subscriber");
        List<Throwable> refusals = new ArrayList<>();

        transcript().emit(event(1L), (sent, refused) -> refusals.add(refused));

        assertTrue(refusals.isEmpty(),
                "a send that never reached the broker cannot complete; it is logged here instead");
    }

    @Test
    void aTranscriptThatCannotBeWrittenNeverStopsTheRun() {
        // A transcript is what an operator watches, not what a run depends on. Throwing back at the
        // caller would end a log reader, or fail a control channel that must keep delivering cancels.
        emitter.throwsOnSend = new IllegalStateException("no subscriber");

        transcript().emit(event(1L), (sent, refused) -> {
        });
    }
}
