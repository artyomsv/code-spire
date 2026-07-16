package dev.codespire.orchestrator;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Split test for the conversational loop (orchestrator half): an EXPLAIN-level provider + an owned thread,
 * a human reply on cs.integration, and the orchestrator must emit an AnswerFollowUp on cs.commands. Mirrors
 * {@link OrchestratorChoreographyTest} — the test plays gateway (publishes AuthorReplied) and asserts the
 * orchestrator's command. The worker half (FollowUpPosted) belongs to the review-worker deployable's tests.
 */
@QuarkusTest
class ConversationE2ETest {

    private static final RepoRef REPO = new RepoRef("convo-ws", "convo-repo");
    private static final String REVIEW_ID = "review::convo-ws/convo-repo#5";
    private static final String THREAD_REF = "100";

    @ConfigProperty(name = "kafka.bootstrap.servers")
    String bootstrapServers;

    @Inject
    ObjectMapper mapper;

    @Inject
    DataSource dataSource;

    @Inject
    dev.codespire.orchestrator.provider.ProviderRegistry providers;

    @Inject
    dev.codespire.orchestrator.llm.LlmProviderRegistry llmProviders;

    private KafkaProducer<String, String> producer;

    @BeforeEach
    void seed() throws Exception {
        // EXPLAIN provider on the reply's workspace, empty allowlist (answers everyone), a bot id that is
        // NOT the reply author (so the ADR-013 self-loop guard doesn't drop it).
        try {
            providers.create(new dev.codespire.orchestrator.provider.ProviderInput(
                    "convo", "github", "https://api.github.com", "convo-ws", "bearer", null, "tok",
                    "bot-account-1", true, List.of(), null, "EXPLAIN"));
        } catch (RuntimeException alreadyRegistered) {
            // one provider per (type, workspace)
        }
        // planFollowUp needs a default LLM provider to pack a credential (else it skips).
        if (llmProviders.resolveDefault().isEmpty()) {
            llmProviders.create(new dev.codespire.orchestrator.llm.LlmProviderInput(
                    "test-llm", "openai", "http://localhost", "sk-test", "gpt-4o", 0.2, null, true, true));
        }
        // Mark the thread as bot-owned (scope A) so the reply is in-scope.
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "INSERT INTO review_thread (review_id, thread_ref, is_ours) VALUES (?, ?, TRUE) "
                             + "ON CONFLICT (review_id, thread_ref) DO UPDATE SET is_ours = TRUE")) {
            ps.setString(1, REVIEW_ID);
            ps.setString(2, THREAD_REF);
            ps.executeUpdate();
        }
    }

    @AfterEach
    void closeProducer() {
        if (producer != null) {
            producer.close();
        }
    }

    @Test
    void replyInOwnedThreadYieldsAnswerFollowUp() throws Exception {
        IntegrationEvent reply = new IntegrationEvent.AuthorReplied(
                REPO, 5, REVIEW_ID, new ThreadRef(THREAD_REF), "200", "why is this a bug?",
                Author.of("human-account-1", "octocat", "Octo Cat"));
        produce(reply);

        String command = awaitCommand("\"type\":\"AnswerFollowUp\"", REVIEW_ID);
        assertNotNull(command, "an AnswerFollowUp must be emitted for a reply in an owned EXPLAIN thread");
        assertTrue(command.contains("\"threadRef\""), "the AnswerFollowUp must carry the thread ref");
    }

    private void produce(IntegrationEvent event) throws Exception {
        if (producer == null) {
            Properties props = new Properties();
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
            producer = new KafkaProducer<>(props);
        }
        String json = mapper.writerFor(IntegrationEvent.class).writeValueAsString(event);
        producer.send(new ProducerRecord<>("cs.integration", REVIEW_ID, json)).get();
    }

    /** Polls cs.commands for a record matching both markers (robust to other tests' traffic on the topic). */
    private String awaitCommand(String typeMarker, String reviewIdMarker) {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());

        long deadline = System.currentTimeMillis() + 30_000;
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of("cs.commands"));
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value().contains(typeMarker) && record.value().contains(reviewIdMarker)) {
                        return record.value();
                    }
                }
            }
        }
        return null;
    }
}
