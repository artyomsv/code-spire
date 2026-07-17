package dev.codespire.orchestrator.dlq;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.util.Properties;
import java.util.concurrent.ExecutionException;

/**
 * Re-publishes a dead-letter entry's exact bytes to its original topic (DlqTopics.forType)
 * so the normal consumer reprocesses it. A raw {@link KafkaProducer} rather than a SmallRye
 * {@code @Channel} Emitter, because the destination topic varies per record — the outgoing
 * channels wired in application.yml are each bound to one fixed topic.
 */
@ApplicationScoped
public class DlqReplayProducer {

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    private volatile KafkaProducer<String, String> producer;

    public void publish(String topic, String key, String payload) {
        try {
            producer().send(new ProducerRecord<>(topic, key, payload)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while replaying a dead-letter entry to " + topic, e);
        } catch (ExecutionException e) {
            throw new IllegalStateException("Failed to replay a dead-letter entry to " + topic, e.getCause());
        }
    }

    private KafkaProducer<String, String> producer() {
        KafkaProducer<String, String> current = producer;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            if (producer == null) {
                Properties props = new Properties();
                props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
                props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
                producer = new KafkaProducer<>(props);
            }
            return producer;
        }
    }

    @PreDestroy
    void close() {
        KafkaProducer<String, String> current = producer;
        if (current != null) {
            current.close();
        }
    }
}
