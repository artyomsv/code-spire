package dev.codespire.orchestrator.work;

import org.eclipse.microprofile.config.Config;
import org.eclipse.microprofile.config.ConfigProvider;
import org.postgresql.PGConnection;
import org.postgresql.PGNotification;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * A session of its own that listens on the work-item change channel. Not a pooled connection: a LISTEN left on a pooled
 * session would keep collecting notices for whichever test borrowed it next.
 */
final class NoticeTap implements AutoCloseable {

    static final String CHANNEL = "work_item_changed";

    private final Connection connection;

    private NoticeTap(Connection connection) {
        this.connection = connection;
    }

    /** Open after the test's own seeding, then {@link #drain} once: the fixture's saves send notices of their own. */
    static NoticeTap listen() throws SQLException {
        Connection connection = connect("spire-notice-tap");
        try (Statement statement = connection.createStatement()) {
            statement.execute("LISTEN " + CHANNEL);
        }
        return new NoticeTap(connection);
    }

    /** A connection outside the pool, to the same database the application uses, named so a test can find it. */
    static Connection connect(String applicationName) throws SQLException {
        Config config = ConfigProvider.getConfig();
        Properties properties = new Properties();
        properties.setProperty("user", config.getValue("quarkus.datasource.username", String.class));
        properties.setProperty("password", config.getValue("quarkus.datasource.password", String.class));
        properties.setProperty("ApplicationName", applicationName);
        Connection connection = DriverManager.getConnection(config.getValue("quarkus.datasource.jdbc.url", String.class), properties);
        connection.setAutoCommit(true);
        return connection;
    }

    /** Every payload received until none has arrived for {@code quiet}. */
    List<String> drain(Duration quiet) throws SQLException {
        List<String> payloads = new ArrayList<>();
        while (true) {
            PGNotification[] notices = connection.unwrap(PGConnection.class).getNotifications((int) quiet.toMillis());
            if (notices == null || notices.length == 0) return payloads;
            for (PGNotification notice : notices) payloads.add(notice.getParameter());
        }
    }

    @Override
    public void close() throws SQLException {
        connection.close();
    }
}
