package dev.codespire.runworker;

import dev.codespire.contract.command.HarnessImageCommand;
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;
import org.jboss.logging.Logger;

/** Never throws on a poison record, for the reason {@code RunCommandDeserializer} gives. */
public class HarnessImageCommandDeserializer extends ObjectMapperDeserializer<HarnessImageCommand> {

    private static final Logger LOG = Logger.getLogger(HarnessImageCommandDeserializer.class);

    public HarnessImageCommandDeserializer() {
        super(HarnessImageCommand.class);
    }

    @Override
    public HarnessImageCommand deserialize(String topic, byte[] data) {
        try {
            return super.deserialize(topic, data);
        } catch (RuntimeException undeserializable) {
            LOG.errorf(undeserializable, "dropping an undeserializable record on %s", topic);
            return null;
        }
    }
}
