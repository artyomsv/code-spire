package dev.codespire.runworker;

import dev.codespire.contract.event.RunEventRecord;
import io.smallrye.reactive.messaging.annotations.OnOverflow;
import io.smallrye.reactive.messaging.kafka.Record;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.jboss.logging.Logger;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The one writer to the run transcript.
 *
 * <p>One, because SmallRye allows a single emitter per outgoing channel — a second injection of
 * {@code run-events-out} fails the whole deployment at build time. That constraint happens to point
 * at the right shape anyway: the transcript now has two producers, the agent's own output and an
 * operator's interventions, and they belong behind one door rather than two copies of the send.
 *
 * <p>Nothing here throws. A transcript is what an operator watches, not what a run depends on, so a
 * line that could not be written must never stop the run or the control record that produced it.
 */
@ApplicationScoped
public class RunTranscript {

    private static final Logger LOG = Logger.getLogger(RunTranscript.class);

    /**
     * Sequence numbers for lines this worker authors, separate from the agent's.
     *
     * <p>The launcher's fold numbers what the agent emitted; these are the worker's own notes about
     * the run. Sharing a counter would make a gap in one look like a loss in the other.
     */
    private final AtomicLong sequence = new AtomicLong();

    /**
     * DROP on overflow, carried over from the emitter this replaced.
     *
     * <p>The default buffers and then FAILS the stream, and this emitter is shared by every
     * concurrent run — so one chatty agent would end the transcript for all of them until the
     * worker restarted. Dropping one event is the smaller loss, and the launcher's gap warning
     * is what tells an operator it happened.
     */
    @Inject
    @Channel("run-events-out")
    @OnOverflow(OnOverflow.Strategy.DROP)
    Emitter<Record<String, RunEventRecord>> events;

    /**
     * Publish one already-formed event, from the agent's own stream.
     *
     * <p>The completion is handed back rather than awaited: the channel does not wait for write
     * completion, so a broker refusal arrives there and nowhere else, and the launcher's gap warning
     * depends on seeing it.
     */
    public void emit(RunEventRecord event, java.util.function.BiConsumer<Void, Throwable> onComplete) {
        try {
            events.send(Record.of(event.runId(), event)).whenComplete(onComplete);
        } catch (RuntimeException e) {
            LOG.warnf("run %s: transcript event %d could not be sent (%s)",
                    event.runId(), event.sequence(), e.getClass().getSimpleName());
        }
    }

    /**
     * Put one of this worker's own notes on a run's transcript.
     *
     * <p>{@code error} marks a line an operator needs to notice — a refused instruction, rather than
     * a delivered one — so it stands out on a live tail rather than scrolling past among the agent's
     * output.
     */
    public void note(String runId, String kind, String text, boolean error) {
        try {
            events.send(Record.of(runId, new RunEventRecord(runId, sequence.incrementAndGet(),
                    Instant.now(), kind, clip(text), error)));
        } catch (RuntimeException e) {
            LOG.warnf("run %s: a control note could not be put on its transcript (%s)",
                    runId, e.getClass().getSimpleName());
        }
    }

    /** The wire's own bound, applied here because this text is an operator's rather than an agent's. */
    private static String clip(String text) {
        return text.length() <= RunEventRecord.MAX_TEXT_CHARS
                ? text
                : text.substring(0, RunEventRecord.MAX_TEXT_CHARS - RunEventRecord.CLIPPED.length())
                        + RunEventRecord.CLIPPED;
    }
}
