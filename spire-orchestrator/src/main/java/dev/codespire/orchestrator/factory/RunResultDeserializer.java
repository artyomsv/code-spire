package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunResult;
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;
import org.jboss.logging.Logger;

/**
 * cs.run-results wire format. NEVER throws: a poison record is logged and mapped to null so the
 * consumer stays alive and the handler skips it — a deserializer that throws kills the consumer and
 * the record is redelivered on every restart, which this project has already cleared by hand once.
 */
public class RunResultDeserializer extends ObjectMapperDeserializer<RunResult> {

    private static final Logger LOG = Logger.getLogger(RunResultDeserializer.class);

    public RunResultDeserializer() {
        super(RunResult.class);
    }

    @Override
    public RunResult deserialize(String topic, byte[] data) {
        try {
            return super.deserialize(topic, data);
        } catch (RuntimeException e) {
            // ERROR: the record is dropped for good, and a run that depended on it stays "running"
            // in the read model until an operator notices. This must surface loudly.
            LOG.errorf(e, "Dropping undeserializable run result on %s", topic);
            return null;
        }
    }
}
