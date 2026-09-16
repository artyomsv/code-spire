package dev.codespire.runworker;

import dev.codespire.contract.command.HarnessSignInCommand;
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;
import org.jboss.logging.Logger;

/**
 * Never throws on a poison record, for the reason {@code RunCommandDeserializer} spells out: a
 * deserializer that throws kills the consumer, and the record is redelivered on every restart.
 */
public class HarnessSignInCommandDeserializer extends ObjectMapperDeserializer<HarnessSignInCommand> {

    private static final Logger LOG = Logger.getLogger(HarnessSignInCommandDeserializer.class);

    public HarnessSignInCommandDeserializer() {
        super(HarnessSignInCommand.class);
    }

    @Override
    public HarnessSignInCommand deserialize(String topic, byte[] data) {
        try {
            return super.deserialize(topic, data);
        } catch (RuntimeException undeserializable) {
            // ERROR: the sign-in it names never starts, and an operator is waiting on a screen for it.
            LOG.errorf(undeserializable, "dropping an undeserializable record on %s", topic);
            return null;
        }
    }
}
