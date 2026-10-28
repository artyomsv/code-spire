package dev.codespire.orchestrator.work;

import java.sql.Connection;
import java.sql.SQLException;

/** Listens for the database's work-item change notices and hands them on in batches. */
public class WorkItemChangeListener {

    static final long BATCH_MS = 250;

    /** Opens the listening session; a new one after every drop. */
    interface Connector {
        Connection open() throws SQLException;
    }

    void start(WorkItemChangeSink sink, Connector connector) {
    }

    void stop() {
    }
}
