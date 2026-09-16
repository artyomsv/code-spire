package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.HarnessSignInResult;
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;
import org.jboss.logging.Logger;

/**
 * Never throws on a poison record: a deserializer that throws kills the consumer and the record is
 * redelivered on every restart. Answering null dead-letters it and keeps the screen's channel alive.
 */
public class HarnessSignInResultDeserializer extends ObjectMapperDeserializer<HarnessSignInResult> {

    private static final Logger LOG = Logger.getLogger(HarnessSignInResultDeserializer.class);

    public HarnessSignInResultDeserializer() {
        super(HarnessSignInResult.class);
    }

    @Override
    public HarnessSignInResult deserialize(String topic, byte[] data) {
        try {
            return super.deserialize(topic, data);
        } catch (RuntimeException undeserializable) {
            // ERROR: an operator is watching a screen for this, and it will now never arrive.
            LOG.errorf(undeserializable, "dropping an undeserializable record on %s", topic);
            return null;
        }
    }
}
