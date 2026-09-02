package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunEventRecord;
import io.quarkus.kafka.client.serialization.ObjectMapperDeserializer;

/**
 * Never throws on a bad record.
 *
 * <p>The base class answers null for anything it cannot read, and the consumer treats null as
 * nothing to record. A poison record on this topic must not kill the consumer: the transcript is a
 * convenience, and one unreadable line is not worth stalling every other run's tail.
 */
public class RunEventDeserializer extends ObjectMapperDeserializer<RunEventRecord> {

    public RunEventDeserializer() {
        super(RunEventRecord.class);
    }
}
