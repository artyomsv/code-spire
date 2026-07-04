package dev.codespire.worker.pipeline;

import dev.codespire.contract.command.ActionCommand;
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;
import org.jboss.logging.Logger;

/**
 * cs.commands wire format: polymorphic JSON via the contract's type
 * discriminator. NEVER throws: a poison record (malformed JSON, unknown future
 * type) is logged and mapped to null — the consumer stays alive and the
 * dispatcher skips it (review finding H1/M1); processing failures go to cs.dlq.
 */
public class ActionCommandDeserializer extends ObjectMapperDeserializer<ActionCommand> {

    private static final Logger LOG = Logger.getLogger(ActionCommandDeserializer.class);

    public ActionCommandDeserializer() {
        super(ActionCommand.class);
    }

    @Override
    public ActionCommand deserialize(String topic, byte[] data) {
        try {
            return super.deserialize(topic, data);
        } catch (RuntimeException e) {
            LOG.warnf(e, "Dropping undeserializable record on %s", topic);
            return null;
        }
    }
}
