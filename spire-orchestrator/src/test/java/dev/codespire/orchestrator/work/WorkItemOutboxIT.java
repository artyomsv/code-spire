package dev.codespire.orchestrator.work;

import dev.codespire.encryption.EncryptionService;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.*;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.clients.producer.*;
import org.apache.kafka.common.serialization.*;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.*;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.awaitility.Awaitility.await;

@QuarkusTest
class WorkItemOutboxIT extends WorkFixture {
    @Inject WorkItemOutbox outbox;
    @Inject EncryptionService encryption;
    @ConfigProperty(name="kafka.bootstrap.servers") String bootstrap;

    @Test void aBrokerDeliveryEntersOnlyTheWorkConsumer() throws Exception {
        String body=mapper.writeValueAsString(signed("900123"));
        try(KafkaProducer<String,String> producer=new KafkaProducer<>(Map.of(
                ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,bootstrap,ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG,StringSerializer.class,
                ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG,StringSerializer.class));
            AdminClient admin=AdminClient.create(Map.of(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG,bootstrap))) {
            producer.send(new ProducerRecord<>("cs.work-integration",itemId,body)).get(15,TimeUnit.SECONDS);
            RecordMetadata duplicate=producer.send(new ProducerRecord<>("cs.work-integration",itemId,body)).get(15,TimeUnit.SECONDS);
            TopicPartition partition=new TopicPartition(duplicate.topic(),duplicate.partition());
            await().atMost(Duration.ofSeconds(20)).untilAsserted(()->{
                OffsetAndMetadata processed=admin.listConsumerGroupOffsets("spire-orchestrator-work")
                        .partitionsToOffsetAndMetadata().get(5,TimeUnit.SECONDS).get(partition);
                assertNotNull(processed,"the consumer must acknowledge the duplicate before fixture cleanup");
                assertTrue(processed.offset()>duplicate.offset());
            });
            await().atMost(Duration.ofSeconds(20)).untilAsserted(()->assertEquals(1,count("SELECT count(*) FROM work_item WHERE id=?",itemId)));
            assertEquals(0,count("SELECT count(*) FROM review_status WHERE repository_id=?",repository));
            assertEquals(1,store.history(itemId).size());
        }
    }

    @Test void publishedWorkNotificationsUseTheWorkTopicAndAreNotPublishedAgain() throws Exception {
        intake.accept(signed("900123"));
        outbox.publish();outbox.publish();
        assertEquals(1,count("SELECT count(*) FROM work_item_outbox WHERE work_item_id=? AND published_at IS NOT NULL",itemId));
        List<ConsumerRecord<String,String>> own=new ArrayList<>();
        try(KafkaConsumer<String,String> consumer=new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,bootstrap,ConsumerConfig.GROUP_ID_CONFIG,"TEST-work-notifications-"+UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class,ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG,StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG,"earliest"))) {
            consumer.subscribe(List.of("cs.work-events"));
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);
            while(System.nanoTime()<deadline && own.isEmpty()) for(ConsumerRecord<String,String> record:consumer.poll(Duration.ofMillis(500)))
                if(itemId.equals(record.key()))own.add(record);
            for(ConsumerRecord<String,String> record:consumer.poll(Duration.ofSeconds(1)))if(itemId.equals(record.key()))own.add(record);
        }
        assertEquals(1,own.size(),"the real outbox must publish exactly one work-topic notification after two sweeps");
        assertEquals("WorkItemEvent",mapper.readTree(own.getFirst().value()).path("eventType").asText());
        assertEquals(itemId,mapper.readTree(own.getFirst().value()).path("payload").path("workItemId").asText());
        assertEquals(0,count("SELECT count(*) FROM review_status WHERE repository_id=?",repository));
    }

    @Test void anUnacknowledgedNotificationRemainsPending() throws Exception {
        intake.accept(signed("900123"));
        WorkItemOutbox rejected=new WorkItemOutbox();rejected.dataSource=dataSource;rejected.encryption=encryption;
        rejected.emitter=(Emitter<String>)Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{Emitter.class},(proxy,method,args)->{
            if(method.getName().equals("send") && args[0] instanceof Message<?> message){message.nack(new IllegalArgumentException("TEST-rejected"));return null;}
            throw new UnsupportedOperationException(method.getName());
        });
        assertThrows(RuntimeException.class,rejected::publish);
        assertEquals(1,count("SELECT count(*) FROM work_item_outbox WHERE work_item_id=? AND published_at IS NULL",itemId));
    }

    @Test void outboxPayloadIsEncryptedAndBoundToTheEffect() throws Exception {
        intake.accept(signed("900123"));
        try(Connection c=dataSource.getConnection();PreparedStatement ps=c.prepareStatement("SELECT effect_id,payload FROM work_item_outbox WHERE work_item_id=?")) {
            ps.setString(1,itemId);
            try(ResultSet rs=ps.executeQuery()) {
                assertTrue(rs.next());byte[] payload=rs.getBytes(2);
                assertFalse(new String(payload,StandardCharsets.UTF_8).contains(itemId));
                assertTrue(new String(encryption.decrypt(payload,"work-effect:"+rs.getObject(1)),StandardCharsets.UTF_8).contains(itemId));
                assertThrows(RuntimeException.class,()->encryption.decrypt(payload,"work-effect:TEST-other-effect"));
            }
        }
    }
}
