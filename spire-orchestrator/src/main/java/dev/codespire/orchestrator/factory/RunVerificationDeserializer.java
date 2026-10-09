package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunVerification;
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;
import org.jboss.logging.Logger;

/**
 * cs.run-verifications wire format. NEVER throws: a poison record is logged and mapped to null so the
 * consumer stays alive and the handler skips it — a deserializer that throws kills the consumer and
 * the record is redelivered on every restart, which this project has already cleared by hand once.
 */
public class RunVerificationDeserializer extends ObjectMapperDeserializer<RunVerification> {

    private static final Logger LOG = Logger.getLogger(RunVerificationDeserializer.class);

    public RunVerificationDeserializer() {
        super(RunVerification.class);
    }

    @Override
    public RunVerification deserialize(String topic, byte[] data) {
        try {
            return super.deserialize(topic, data);
        } catch (RuntimeException e) {
            // ERROR: the record is dropped for good, and the verify attempt stays "sent"
            // until an operator notices; nothing infers a result. This must surface loudly.
            LOG.errorf(e, "Dropping undeserializable run verification on %s", topic);
            return null;
        }
    }
}
