package dev.codespire.runworker;

import dev.codespire.contract.event.RunEventRecord;
import io.quarkus.kafka.client.serialization.ObjectMapperSerializer;

/** JSON on {@code cs.run-events}, keyed by runId like every other message in this system. */
public class RunEventSerializer extends ObjectMapperSerializer<RunEventRecord> {
}
