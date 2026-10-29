package dev.codespire.orchestrator.work;

import io.quarkus.runtime.StartupEvent;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;

/**
 * Hands the database's work-item change notices to the sink in batches (spec §4.2).
 *
 * <p>One session of its own, outside the pool: a pooled connection held for ever would shrink the pool, and a LISTEN
 * left on a pooled session would follow it to the next borrower. The session runs on the default schema and is used
 * for LISTEN only. After every (re)connect the LISTEN is committed before the refresh, so a change made in between is
 * notified rather than lost.
 */
@ApplicationScoped
public class WorkItemChangeListener {

    static final long BATCH_MS = 250;
    static final String CHANNEL = "work_item_changed";
    /** How long one read waits for a first notice before it checks whether the listener was stopped. */
    private static final int IDLE_READ_MS = 1000;
    /** Empty idle reads before the session is probed; package-private so a test need not wait half a minute. */
    int probeAfterIdleReads = 30;
    private static final Duration FIRST_BACK_OFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACK_OFF = Duration.ofSeconds(30);
    private static final Duration STOP_WAIT = Duration.ofSeconds(2);
    private static final Logger LOG = Logger.getLogger(WorkItemChangeListener.class);

    /** Opens the listening session; a new one after every drop. */
    interface Connector {
        Connection open() throws SQLException;
    }

    @Inject
    WorkItemsBroadcaster broadcaster;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @ConfigProperty(name = "quarkus.datasource.username")
    String username;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    private volatile boolean running;
    private volatile Thread worker;

    void onStart(@Observes StartupEvent started) {
        start(broadcaster, this::connect);
    }

    synchronized void start(WorkItemChangeSink sink, Connector connector) {
        if (worker != null) return;
        running = true;
        worker = Thread.ofVirtual().name("work-item-change-listener").start(() -> run(sink, connector));
    }

    @PreDestroy
    synchronized void stop() {
        running = false;
        Thread current = worker;
        worker = null;
        if (current == null) return;
        // The worker closes its own session: closing it from here could wait on the driver lock the blocked read holds.
        current.interrupt();
        try {
            current.join(STOP_WAIT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void run(WorkItemChangeSink sink, Connector connector) {
        Duration backOff = FIRST_BACK_OFF;
        boolean first = true;
        while (running) {
            try (Connection connection = connector.open()) {
                listen(connection);
                LOG.info(first ? "work-item change feed listening" : "work-item change feed listening again");
                first = false;
                backOff = FIRST_BACK_OFF;
                deliver(sink, Set.of("*"));
                receive(connection, sink);
            } catch (SQLException failure) {
                if (!running) return;
                LOG.warnf("work-item change feed lost its database session (%s); reconnecting in %s", failure.getMessage(), backOff);
                backOff = waitBeforeRetry(backOff);
            } catch (RuntimeException failure) {
                // Not a lost session but a fault: still reconnect, because a dead thread would leave every screen deaf.
                if (!running) return;
                LOG.errorf(failure, "work-item change feed failed; reconnecting in %s", backOff);
                backOff = waitBeforeRetry(backOff);
            }
        }
    }

    /** Sleeps the back-off and returns the next one; a stop during the sleep ends the loop. */
    private Duration waitBeforeRetry(Duration backOff) {
        if (!pause(backOff)) return backOff;
        Duration doubled = backOff.multipliedBy(2);
        return doubled.compareTo(MAX_BACK_OFF) > 0 ? MAX_BACK_OFF : doubled;
    }

    private static void listen(Connection connection) throws SQLException {
        connection.setAutoCommit(true);
        try (Statement statement = connection.createStatement()) {
            statement.execute("LISTEN " + CHANNEL);
        }
    }

    /**
     * Reads notices until the session fails; the first notice of a batch opens a 250 ms window for the rest. A quiet
     * session is probed: a network device that drops an idle connection sends no error, so without a question the read
     * would only time out, for ever.
     */
    private void receive(Connection connection, WorkItemChangeSink sink) throws SQLException {
        PGConnection notices = connection.unwrap(PGConnection.class);
        int quietReads = 0;
        while (running) {
            Set<String> batch = new HashSet<>();
            collect(notices.getNotifications(IDLE_READ_MS), batch);
            if (batch.isEmpty()) {
                if (++quietReads >= probeAfterIdleReads) {
                    probe(connection);
                    quietReads = 0;
                }
                continue;
            }
            quietReads = 0;
            long closes = System.nanoTime() + Duration.ofMillis(BATCH_MS).toNanos();
            for (long left = closes - System.nanoTime(); left > 0; left = closes - System.nanoTime()) {
                collect(notices.getNotifications((int) Math.max(1, Duration.ofNanos(left).toMillis())), batch);
            }
            deliver(sink, batch);
        }
    }

    private static void probe(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
        }
    }

    private static void collect(PGNotification[] notices, Set<String> batch) {
        if (notices == null) return;
        for (PGNotification notice : notices) batch.add(notice.getParameter());
    }

    private static void deliver(WorkItemChangeSink sink, Set<String> batch) {
        if (batch.isEmpty()) return;
        try {
            if (batch.contains("*")) sink.refreshAll();
            else sink.changed(batch);
        } catch (RuntimeException failure) {
            // A sink fault costs one batch; the screens re-read on the next notice or reconnect.
            LOG.warnf(failure, "work-item change feed could not deliver %d notices", batch.size());
        }
    }

    private boolean pause(Duration backOff) {
        try {
            Thread.sleep(backOff);
            return running;
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private Connection connect() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("ApplicationName", "spire-work-item-listener");
        // Pool-level settings (quarkus.datasource.jdbc.additional-jdbc-properties) do not reach this session: TLS options
        // belong in the JDBC URL. Keep-alive alone is slow to notice a dead peer; receive() also probes a quiet session.
        properties.setProperty("tcpKeepAlive", "true");
        return DriverManager.getConnection(url, properties);
    }
}
