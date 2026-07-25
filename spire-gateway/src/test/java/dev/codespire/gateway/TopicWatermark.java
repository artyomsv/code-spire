package dev.codespire.gateway;

import io.smallrye.reactive.messaging.kafka.companion.KafkaCompanion;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.common.TopicPartition;

import java.util.HashMap;
import java.util.Map;

/**
 * Where a test's own records begin on a shared topic.
 *
 * <p>One Kafka broker is shared by every {@code @QuarkusTest} class in the module, so a consumer that
 * starts at the beginning of a topic reads whichever class wrote first — not this test's record. That
 * made the "lands on the topic" assertions pass alone and fail in the full suite, depending on class
 * order. Capture the end offsets BEFORE the action under test and consume from there, so a test only
 * ever asserts on records its own action produced.
 */
final class TopicWatermark {

    private TopicWatermark() {
    }

    /** End offsets per partition now; all-zero when the topic does not exist yet (nothing to skip). */
    static Map<TopicPartition, Long> of(KafkaCompanion companion, String topic) {
        if (!companion.topics().list().contains(topic)) {
            return Map.of(new TopicPartition(topic, 0), 0L);
        }
        Map<TopicPartition, Long> offsets = new HashMap<>();
        companion.topics().describe(topic).get(topic).partitions().forEach(partition -> {
            TopicPartition tp = new TopicPartition(topic, partition.partition());
            offsets.put(tp, companion.offsets().get(tp, OffsetSpec.latest()).offset());
        });
        return offsets;
    }
}
