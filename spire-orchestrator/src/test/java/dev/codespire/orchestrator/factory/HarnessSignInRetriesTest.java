package dev.codespire.orchestrator.factory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.HarnessSignInResult;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/**
 * A sign-in nobody picked up (review of PR #168).
 *
 * <p>A start sent while the worker's group was still being assigned its partition was never read, and
 * nothing ended the row: it stayed PENDING, and the screen said "starting" for ever. These are the two
 * ways out — send it again while its wait lasts, fail it once the wait is gone — and the matching exit
 * for a prompt whose code ran out without the worker saying so.
 */
@QuarkusTest
class HarnessSignInRetriesTest {

    @Inject HarnessSignIns signIns;
    @Inject DataSource dataSource;
    @Inject ObjectMapper mapper;
    @ConfigProperty(name = "kafka.bootstrap.servers") String bootstrap;

    private static final String OWNED = "TEST-retry-%";

    @AfterEach
    void clean() throws SQLException {
        try (Connection c = dataSource.getConnection(); Statement s = c.createStatement()) {
            s.executeUpdate("DELETE FROM harness_sign_in WHERE label LIKE '" + OWNED + "'");
        }
    }

    private UUID start(String label) {
        HarnessSignIns.Started started = signIns.start(label, "codex", "TEST-operator");
        assertNull(started.refusal(), started.refusal());
        return started.view().id();
    }

    /** Moves the operator's press into the past, as if the start had gone unanswered that long. */
    private Instant pressedAgo(UUID id, Duration ago) throws SQLException {
        Instant pressed = Instant.now().minus(ago).truncatedTo(ChronoUnit.MILLIS);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("UPDATE harness_sign_in SET created_at=? WHERE id=?")) {
            ps.setTimestamp(1, Timestamp.from(pressed)); ps.setObject(2, id); ps.executeUpdate();
        }
        return pressed;
    }

    /** Every start sent for this sign-in, oldest first. */
    private List<JsonNode> startsFor(UUID id, int atLeast) throws Exception {
        List<JsonNode> own = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                ConsumerConfig.GROUP_ID_CONFIG, "TEST-sign-in-retries-" + UUID.randomUUID(),
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"))) {
            consumer.subscribe(List.of("cs.harness-sign-in-commands"));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline && own.size() < atLeast) collect(consumer, id, own, Duration.ofMillis(500));
            collect(consumer, id, own, Duration.ofSeconds(1));
        }
        return own;
    }

    private void collect(KafkaConsumer<String, String> consumer, UUID id, List<JsonNode> own, Duration wait) throws Exception {
        for (ConsumerRecord<String, String> record : consumer.poll(wait)) {
            if (!id.toString().equals(record.key())) continue;
            JsonNode value = mapper.readTree(record.value());
            if ("Start".equals(value.path("type").asText())) own.add(value);
        }
    }

    @Test
    void aStartNobodyPickedUpIsSentAgainCountedFromTheOriginalPress() throws Exception {
        UUID id = start("TEST-retry-resend");
        Instant pressed = pressedAgo(id, Duration.ofSeconds(60));

        signIns.resendUnclaimed();

        List<JsonNode> starts = startsFor(id, 2);
        assertEquals(2, starts.size(), "the original start and one re-send");
        assertFalse(starts.get(0).path("requestedAt").isNull() || starts.get(0).path("requestedAt").isMissingNode(),
                "the first start carries the press time too");
        // The wait is anchored to the press, so a worker receiving this late gives it only what is left.
        assertEquals(pressed, mapper.convertValue(starts.get(1).path("requestedAt"), Instant.class));
        // The window the worker must open a unit in is the one this side ends unclaimed rows by.
        assertEquals(HarnessSignIns.START_WITHIN.toSeconds(), starts.get(0).path("startWithinSeconds").asLong());
        assertEquals(HarnessSignIns.START_WITHIN.toSeconds(), starts.get(1).path("startWithinSeconds").asLong());
        assertEquals("PENDING", signIns.get(id).orElseThrow().state());
    }

    @Test
    void aStartStillUnansweredWhenItsWaitRanOutFailsAndSaysWhy() throws Exception {
        UUID id = start("TEST-retry-expired");
        pressedAgo(id, HarnessSignIns.UNCLAIMED_DEADLINE.plusSeconds(30));

        signIns.resendUnclaimed();

        HarnessSignIns.View view = signIns.get(id).orElseThrow();
        assertEquals("FAILED", view.state());
        assertEquals(HarnessSignInResult.Failed.NOT_STARTED, view.reason());
        assertEquals(1, startsFor(id, 1).size(), "a sign-in whose wait is gone is not sent again");
    }

    /** Past the start window a worker opens nothing, so nothing is sent; the row waits for its deadline. */
    @Test
    void aStartPastItsWindowIsNotSentAgainButNotYetEnded() throws Exception {
        UUID id = start("TEST-retry-window-closed");
        pressedAgo(id, HarnessSignIns.START_WITHIN.plusSeconds(30));

        signIns.resendUnclaimed();

        assertEquals(1, startsFor(id, 1).size());
        assertEquals("PENDING", signIns.get(id).orElseThrow().state(), "a code may still be on its way");
    }

    @Test
    void aFreshStartIsNotSentAgainYet() throws Exception {
        UUID id = start("TEST-retry-fresh");

        signIns.resendUnclaimed();

        assertEquals(1, startsFor(id, 1).size());
    }

    /** The worker normally reports an expired code itself; the row must close even when that is lost. */
    @Test
    void aPromptWhoseCodeRanOutIsClosedOnceTheGraceHasPassed() {
        UUID expired = start("TEST-retry-prompt-old");
        signIns.prompted(new HarnessSignInResult.Prompted(expired.toString(), "https://auth.example.test/device",
                "ABCD-12345", Instant.now().minus(HarnessSignIns.EXPIRY_GRACE).minusSeconds(30)));

        signIns.resendUnclaimed();

        HarnessSignIns.View view = signIns.get(expired).orElseThrow();
        assertEquals("FAILED", view.state());
        assertEquals(HarnessSignInResult.Failed.EXPIRED, view.reason());
    }

    @Test
    void aPromptJustPastItsExpiryWaitsForTheWorkerToSaySo() {
        UUID recent = start("TEST-retry-prompt-new");
        signIns.prompted(new HarnessSignInResult.Prompted(recent.toString(), "https://auth.example.test/device",
                "ABCD-12345", Instant.now().minusSeconds(30)));

        signIns.resendUnclaimed();

        assertEquals("PROMPTED", signIns.get(recent).orElseThrow().state());
    }
}
