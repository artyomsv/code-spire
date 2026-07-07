package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent;
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;
import org.jboss.logging.Logger;

/**
 * cs.integration / cs.results wire format: polymorphic JSON via the contract's
 * type discriminator. NEVER throws: a poison record (malformed JSON, unknown
 * future type) is logged and mapped to null — the consumer stays alive and the
 * handler skips it (review finding H1/M1); processing failures go to cs.dlq.
 */
public class IntegrationEventDeserializer extends ObjectMapperDeserializer<IntegrationEvent> {

    private static final Logger LOG = Logger.getLogger(IntegrationEventDeserializer.class);

    public IntegrationEventDeserializer() {
        super(IntegrationEvent.class);
    }

    @Override
    public IntegrationEvent deserialize(String topic, byte[] data) {
        try {
            return super.deserialize(topic, data);
        } catch (RuntimeException e) {
            // ERROR (observability rule): the message is dropped for good — a
            // review that depended on it stalls, so this must surface loudly.
            LOG.errorf(e, "Dropping undeserializable record on %s", topic);
            return null;
        }
    }
}
