package dev.codespire.orchestrator.dlq;

import java.time.Instant;

/** A persisted dead-letter record (V15 {@code dlq_entry}) as the API returns it. */
public record DlqEntry(String id, String kafkaKey, String messageType, String originalTopic,
                       String reason, String payload, String status, Instant createdAt) {
}
