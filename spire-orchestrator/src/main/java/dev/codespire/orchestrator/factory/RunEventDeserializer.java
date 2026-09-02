package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunEventRecord;
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;
import org.jboss.logging.Logger;

/**
 * Never throws on a bad record — by overriding, because the base class does throw.
 *
 * <p>The first version of this class claimed the base class "answers null for anything it cannot
 * read" and relied on that. It does not: {@code ObjectMapperDeserializer} returns null only for a
 * null byte array and wraps anything unparseable in a {@code RuntimeException}. The three
 * established deserializers in this repository all override for exactly this reason.
 *
 * <p>The consequence of getting it wrong here is larger than a lost line. With no
 * {@code failure-strategy} the channel's default is {@code fail}, so one malformed record stops the
 * consumer, is never committed, and returns on every restart — and because the readiness probe
 * covers the whole service, that takes the dashboard, the REST surface and the review pipeline out
 * of rotation over one transcript event. This project has cleared that poison pill by hand once.
 *
 * <p>Logged at WARN rather than ERROR: unlike a dropped command or result, a dropped transcript
 * line stalls nothing.
 */
public class RunEventDeserializer extends ObjectMapperDeserializer<RunEventRecord> {

    private static final Logger LOG = Logger.getLogger(RunEventDeserializer.class);

    public RunEventDeserializer() {
        super(RunEventRecord.class);
    }

    @Override
    public RunEventRecord deserialize(String topic, byte[] data) {
        try {
            return super.deserialize(topic, data);
        } catch (RuntimeException unreadable) {
            LOG.warnf(unreadable, "dropping an unreadable run event on %s", topic);
            return null;
        }
    }
}
