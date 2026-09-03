package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;
import org.jboss.logging.Logger;

/**
 * Never throws on a poison record.
 *
 * <p>A deserializer that throws kills the consumer, and the record is redelivered on every restart —
 * a poison pill that survives restarts, which this project has already had to clear by hand with a
 * manual offset seek. Answering null lets the dispatcher route the record to {@code cs.dlq} instead,
 * where it is visible and the consumer keeps moving.
 *
 * <p><b>The override is the whole class, and shipping without it was the defect.</b> The base
 * {@code deserialize} answers null only for a null byte array; for anything unparseable it wraps the
 * fault and throws, and the messaging layer then fails the channel — {@code failure-strategy} does
 * not apply, because that handles a processing nack rather than a deserialization fault. This class
 * served BOTH worker channels, so one malformed record on {@code cs.run-control} was a worker that
 * could not be cancelled: the outage that topic exists to remove. Its four siblings all override;
 * this one was copied from a sibling and the copy dropped the only line that mattered.
 */
public class RunCommandDeserializer extends ObjectMapperDeserializer<RunCommand> {

    private static final Logger LOG = Logger.getLogger(RunCommandDeserializer.class);

    public RunCommandDeserializer() {
        super(RunCommand.class);
    }

    @Override
    public RunCommand deserialize(String topic, byte[] data) {
        try {
            return super.deserialize(topic, data);
        } catch (RuntimeException undeserializable) {
            // ERROR, not WARN: the command is gone for good. The run it names never starts, and its
            // row stays queued until an operator acts on this line.
            LOG.errorf(undeserializable, "dropping an undeserializable record on %s; it is "
                    + "dead-lettered rather than redelivered for ever", topic);
            return null;
        }
    }
}
