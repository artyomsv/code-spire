package dev.codespire.orchestrator.settings;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@code require} and {@code get} differ on a database fault: {@code get} reads it as unset (a caller with its own
 * default), {@code require} throws (the review path, where "unset" is a final refusal).
 */
class AppSettingRepositoryRequireTest {

    @Test
    void requireThrowsWhereGetReadsEmpty() {
        AppSettingRepository repository = new AppSettingRepository();
        repository.dataSource = unreachable();

        assertThrows(AppSettingRepository.SettingReadFailed.class, () -> repository.require("TEST-key"),
                "a read fault must not look like an unset key");
        assertEquals(Optional.empty(), repository.get("TEST-key"), "get keeps reading a fault as unset");
    }

    /** A DataSource whose every connection attempt fails, as a database that is down does. */
    static DataSource unreachable() {
        return (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(), new Class<?>[] {DataSource.class},
                (proxy, method, args) -> {
                    throw new SQLException("TEST-unreachable");
                });
    }
}
