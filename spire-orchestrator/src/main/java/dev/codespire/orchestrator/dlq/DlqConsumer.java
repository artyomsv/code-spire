package dev.codespire.orchestrator.dlq;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.smallrye.reactive.messaging.annotations.Blocking;
import io.smallrye.reactive.messaging.kafka.api.IncomingKafkaRecordMetadata;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.apache.kafka.common.header.Header;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.jboss.logging.Logger;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

/**
 * Persists every dead-lettered record (cs.dlq) for inspection + manual replay/discard (Step 2).
 * Storage failures here must NEVER re-throw: application.yml wires {@code failure-strategy: ignore}
 * on {@code dlq-in} specifically so a hiccup here cannot loop the record back onto the DLQ it was
 * already dropped from — there is no further queue for it to go to.
 */
@ApplicationScoped
public class DlqConsumer {

    private static final Logger LOG = Logger.getLogger(DlqConsumer.class);
    private static final String REASON_HEADER = "dead-letter-reason";

    @Inject
    DlqRepository repository;

    @Inject
    ObjectMapper mapper;

    @Incoming("dlq-in")
    @Blocking
    public CompletionStage<Void> on(Message<String> record) {
        try {
            persist(record);
        } catch (RuntimeException e) {
            LOG.errorf(e, "Failed to persist a dead-lettered record — dropping it (never re-DLQ)");
        }
        return record.ack();
    }

    private void persist(Message<String> record) {
        String payload = record.getPayload();
        IncomingKafkaRecordMetadata<String, String> metadata =
                record.getMetadata(IncomingKafkaRecordMetadata.class).orElse(null);
        String type = messageType(payload);
        repository.record(UUID.randomUUID(), keyOf(metadata), type, DlqTopics.forType(type),
                reasonOf(metadata), payload);
    }

    private static String keyOf(IncomingKafkaRecordMetadata<String, String> metadata) {
        return metadata == null ? null : metadata.getKey();
    }

    private static String reasonOf(IncomingKafkaRecordMetadata<String, String> metadata) {
        if (metadata == null) {
            return null;
        }
        Header header = metadata.getHeaders().lastHeader(REASON_HEADER);
        return header == null || header.value() == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private String messageType(String payload) {
        if (payload == null) {
            return "";
        }
        try {
            return mapper.readTree(payload).path("type").asText("");
        } catch (JsonProcessingException e) {
            return "";
        }
    }
}
