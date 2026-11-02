package dev.codespire.orchestrator.attention;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;

/**
 * Reads an attention row's {@code since} from a stored column. The only way the rows read one, so none of them can take
 * the evaluation's clock instead: the broadcaster pushes only when the evaluated list changes, and a time that moved
 * with each sweep would push the whole list on every sweep.
 */
public final class StoredTime {

    private StoredTime() {
    }

    /** The column's instant, or null when the column is null. */
    public static Instant of(ResultSet results, String column) throws SQLException {
        Timestamp stored = results.getTimestamp(column);
        return stored == null ? null : stored.toInstant();
    }
}
