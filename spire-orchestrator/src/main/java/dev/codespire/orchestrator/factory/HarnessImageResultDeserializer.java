package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.HarnessImageResult;
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;
import org.jboss.logging.Logger;

/** Never throws on a poison record: a throwing deserializer kills the consumer and redelivers for ever. */
public class HarnessImageResultDeserializer extends ObjectMapperDeserializer<HarnessImageResult> {

    private static final Logger LOG = Logger.getLogger(HarnessImageResultDeserializer.class);

    public HarnessImageResultDeserializer() {
        super(HarnessImageResult.class);
    }

    @Override
    public HarnessImageResult deserialize(String topic, byte[] data) {
        try {
            return super.deserialize(topic, data);
        } catch (RuntimeException undeserializable) {
            LOG.errorf(undeserializable, "dropping an undeserializable record on %s", topic);
            return null;
        }
    }
}
