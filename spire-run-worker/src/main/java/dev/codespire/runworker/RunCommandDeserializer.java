package dev.codespire.runworker;

import dev.codespire.contract.command.RunCommand;
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;

/**
 * Never throws on a poison record.
 *
 * <p>A deserializer that throws kills the consumer, and the record is redelivered on every restart —
 * a poison pill that survives restarts, which this project has already had to clear by hand with a
 * manual offset seek. Answering null lets the dispatcher route the record to {@code cs.dlq} instead,
 * where it is visible and the consumer keeps moving.
 */
public class RunCommandDeserializer extends ObjectMapperDeserializer<RunCommand> {

    public RunCommandDeserializer() {
        super(RunCommand.class);
    }
}
