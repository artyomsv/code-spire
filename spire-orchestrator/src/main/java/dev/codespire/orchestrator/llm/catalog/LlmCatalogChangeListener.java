package dev.codespire.orchestrator.llm.catalog;

import dev.codespire.llmcatalog.CatalogException;
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
import java.util.Objects;
import java.util.Properties;

/**
 * Follows {@code llm_catalog_changed} so every instance swaps to a new catalog at once (spec §6.3).
 *
 * <p>One session of its own, outside the pool, as {@code WorkItemChangeListener} does. After every
 * (re)connect the LISTEN is committed before the row is read, so a change stored in between is either
 * read or notified, never lost. A notice whose sha differs from the catalog in use re-reads the row.
 */
@ApplicationScoped
public class LlmCatalogChangeListener {

    private static final Logger LOG = Logger.getLogger(LlmCatalogChangeListener.class);
    private static final int IDLE_READ_MS = 1000;
    private static final int PROBE_AFTER_IDLE_READS = 30;
    private static final Duration FIRST_BACK_OFF = Duration.ofSeconds(1);
    private static final Duration MAX_BACK_OFF = Duration.ofSeconds(30);
    private static final Duration STOP_WAIT = Duration.ofSeconds(2);

    /** Opens the listening session; a new one after every drop. */
    interface Connector {
        Connection open() throws SQLException;
    }

    @Inject
    LlmModelCatalog catalog;

    @Inject
    LlmCatalogStateRepository states;

    @ConfigProperty(name = "spire.llm.catalog.listener")
    String enabled;

    @ConfigProperty(name = "quarkus.datasource.jdbc.url")
    String url;

    @ConfigProperty(name = "quarkus.datasource.username")
    String username;

    @ConfigProperty(name = "quarkus.datasource.password")
    String password;

    private volatile boolean running;
    private volatile Thread worker;

    void onStart(@Observes StartupEvent started) {
        // With the URL none the row is never used, so there is nothing to follow.
        if ("on".equalsIgnoreCase(enabled) && !catalog.remoteLoadingOff()) {
            start(this::connect);
        }
    }

    synchronized void start(Connector connector) {
        if (worker != null) return;
        running = true;
        worker = Thread.ofVirtual().name("llm-catalog-change-listener").start(() -> run(connector));
    }

    @PreDestroy
    synchronized void stop() {
        running = false;
        Thread current = worker;
        worker = null;
        if (current == null) return;
        current.interrupt();
        try {
            current.join(STOP_WAIT);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private void run(Connector connector) {
        Duration backOff = FIRST_BACK_OFF;
        while (running) {
            try (Connection connection = connector.open()) {
                listen(connection);
                backOff = FIRST_BACK_OFF;
                followTheRow(null);
                receive(connection);
            } catch (SQLException | RuntimeException failure) {
                if (!running) return;
                LOG.warnf("LLM catalog change feed lost its session (%s); reconnecting in %s",
                        failure.getClass().getSimpleName(), backOff);
                backOff = waitBeforeRetry(backOff);
            }
        }
    }

    private static void listen(Connection connection) throws SQLException {
        connection.setAutoCommit(true);
        try (Statement statement = connection.createStatement()) {
            statement.execute("LISTEN " + LlmCatalogStateRepository.CHANNEL);
        }
    }

    private void receive(Connection connection) throws SQLException {
        PGConnection notices = connection.unwrap(PGConnection.class);
        int quietReads = 0;
        while (running) {
            PGNotification[] batch = notices.getNotifications(IDLE_READ_MS);
            if (batch == null || batch.length == 0) {
                if (++quietReads >= PROBE_AFTER_IDLE_READS) {
                    probe(connection);
                    quietReads = 0;
                }
                continue;
            }
            quietReads = 0;
            followTheRow(batch[batch.length - 1].getParameter());
        }
    }

    /**
     * Swaps to the stored document when it differs from the catalog in use. {@code noticedSha} is the sha
     * a notice carried, or null after a (re)connect, when the row is read whatever it holds.
     */
    void followTheRow(String noticedSha) {
        if (noticedSha != null && noticedSha.equals(catalog.snapshot().sha256())) {
            return;
        }
        LlmCatalogState state = states.read();
        if (state.document() == null || Objects.equals(state.sha256(), catalog.snapshot().sha256())) {
            return;
        }
        try {
            catalog.replace(LlmModelCatalog.parse(state.document()));
            LOG.infof("LLM catalog followed to %s", state.sha256());
        } catch (CatalogException e) {
            LOG.errorf("The stored LLM catalog does not read (%s); keeping the catalog in use", CatalogText.safe(e.getMessage()));
        }
    }

    private static void probe(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("SELECT 1");
        }
    }

    private Duration waitBeforeRetry(Duration backOff) {
        try {
            Thread.sleep(backOff);
        } catch (InterruptedException stopped) {
            Thread.currentThread().interrupt();
            return backOff;
        }
        Duration doubled = backOff.multipliedBy(2);
        return doubled.compareTo(MAX_BACK_OFF) > 0 ? MAX_BACK_OFF : doubled;
    }

    private Connection connect() throws SQLException {
        Properties properties = new Properties();
        properties.setProperty("user", username);
        properties.setProperty("password", password);
        properties.setProperty("ApplicationName", "spire-llm-catalog-listener");
        properties.setProperty("tcpKeepAlive", "true");
        return DriverManager.getConnection(url, properties);
    }
}
