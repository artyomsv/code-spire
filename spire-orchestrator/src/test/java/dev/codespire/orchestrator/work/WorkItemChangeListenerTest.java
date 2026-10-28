package dev.codespire.orchestrator.work;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The listener on a real database session of its own (spec §4.2): it listens before it refreshes, batches a burst,
 * survives a killed session, and lets go of its session when stopped. The application's own listener is left alone;
 * this one is named so the test can find and kill exactly its session.
 */
@QuarkusTest
@TestSecurity(user = "TEST-operator", roles = "spire-admin")
class WorkItemChangeListenerTest extends WorkFixture {

    private static final String SESSION = "spire-work-item-listener-test";

    private final WorkItemChangeListener listener = new WorkItemChangeListener();
    private final Recording sink = new Recording();

    @BeforeEach
    void admitTheItem() throws Exception {
        intake.accept(signed("900123"));
    }

    @AfterEach
    void stopTheListener() {
        listener.stop();
    }

    @Test
    void listensBeforeItRefreshes() throws Exception {
        // A change committed between LISTEN and the refresh is neither re-read by the refresh nor lost: it is notified.
        sink.onRefresh = this::touchTheItem;
        start();
        assertTrue(sink.awaitBatchContaining(itemId, Duration.ofSeconds(5)), "the write made during the refresh was not notified");
    }

    @Test
    void aBurstBecomesOneBatch() throws Exception {
        List<String> items = new ArrayList<>();
        for (int i = 0; i < 5; i++) items.add(copyItem(itemId));
        start();
        assertTrue(sink.awaitRefreshes(1, Duration.ofSeconds(5)));
        for (String item : items) executeWith("UPDATE work_item SET reason=? WHERE id=?", "TEST-burst-" + UUID.randomUUID(), item);

        List<Set<String>> batches = sink.awaitUnion(Set.copyOf(items), Duration.ofSeconds(5));
        assertTrue(batches.size() <= 2, "five writes in one loop arrived in " + batches.size() + " batches: " + batches);
        assertTrue(batches.getFirst().size() > 1, "the first batch did not wait for the rest of the burst: " + batches);
    }

    @Test
    void reconnectsAfterTheConnectionIsKilled() throws Exception {
        sink.onRefresh = this::touchTheItem;
        start();
        assertTrue(sink.awaitBatchContaining(itemId, Duration.ofSeconds(5)));

        assertEquals(1, count("SELECT count(pg_terminate_backend(pid)) FROM pg_stat_activity WHERE application_name=?", SESSION));

        assertTrue(sink.awaitRefreshes(2, Duration.ofSeconds(10)), "no refresh after the session was killed");
        assertTrue(sink.awaitBatchContaining(itemId, Duration.ofSeconds(5)), "the write made during the second refresh was lost");
        String later = copyItem(itemId);
        assertTrue(sink.awaitBatchContaining(later, Duration.ofSeconds(5)), "a write after the reconnect was not notified");
    }

    @Test
    void stopClosesTheConnection() throws Exception {
        start();
        assertTrue(sink.awaitRefreshes(1, Duration.ofSeconds(5)));
        assertEquals(1, sessions());

        listener.stop();

        Instant deadline = Instant.now().plusSeconds(2);
        while (sessions() > 0 && Instant.now().isBefore(deadline)) TimeUnit.MILLISECONDS.sleep(50);
        assertEquals(0, sessions(), "the listening session outlived stop()");
    }

    private void start() {
        listener.start(sink, () -> NoticeTap.connect(SESSION));
    }

    private long sessions() throws Exception {
        return count("SELECT count(*) FROM pg_stat_activity WHERE application_name=?", SESSION);
    }

    private void touchTheItem() {
        try {
            executeWith("UPDATE work_item SET reason=? WHERE id=?", "TEST-refresh-" + UUID.randomUUID(), itemId);
        } catch (SQLException failure) {
            throw new IllegalStateException(failure);
        }
    }

    /** Records what the listener hands on; {@link #onRefresh} runs inside every refresh. */
    static final class Recording implements WorkItemChangeSink {
        final BlockingQueue<Set<String>> batches = new LinkedBlockingQueue<>();
        final AtomicInteger refreshes = new AtomicInteger();
        volatile Runnable onRefresh = () -> { };

        @Override
        public void changed(Set<String> ids) {
            batches.add(Set.copyOf(ids));
        }

        @Override
        public void refreshAll() {
            refreshes.incrementAndGet();
            onRefresh.run();
        }

        boolean awaitBatchContaining(String id, Duration timeout) throws InterruptedException {
            Instant deadline = Instant.now().plus(timeout);
            while (Instant.now().isBefore(deadline)) {
                Set<String> batch = batches.poll(50, TimeUnit.MILLISECONDS);
                if (batch != null && batch.contains(id)) return true;
            }
            return false;
        }

        boolean awaitRefreshes(int count, Duration timeout) throws InterruptedException {
            Instant deadline = Instant.now().plus(timeout);
            while (refreshes.get() < count && Instant.now().isBefore(deadline)) TimeUnit.MILLISECONDS.sleep(20);
            return refreshes.get() >= count;
        }

        /** The batches, in order, until together they hold every one of {@code ids}. */
        List<Set<String>> awaitUnion(Set<String> ids, Duration timeout) throws InterruptedException {
            List<Set<String>> seen = new ArrayList<>();
            Set<String> union = new HashSet<>();
            Instant deadline = Instant.now().plus(timeout);
            while (!union.containsAll(ids) && Instant.now().isBefore(deadline)) {
                Set<String> batch = batches.poll(50, TimeUnit.MILLISECONDS);
                if (batch == null) continue;
                seen.add(batch);
                union.addAll(batch);
            }
            assertTrue(union.containsAll(ids), "not every write was notified: " + seen);
            return seen;
        }
    }
}
