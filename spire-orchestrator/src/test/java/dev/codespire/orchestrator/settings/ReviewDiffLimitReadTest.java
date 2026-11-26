package dev.codespire.orchestrator.settings;

import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PR 456, security/M-1: unset is the expensive answer for the Review diff limit, so the credential pack reads it
 * strictly. A database fault and a stored value outside the range both throw rather than read as unset; the settings
 * screen still reads them as unset, so the row can be seen and saved again. No container: a real repository over a
 * database that is down, and a repository holding a given row.
 */
class ReviewDiffLimitReadTest {

    @Test
    void aDatabaseFaultIsNotUnset() {
        ReviewDiffLimit limit = over(unreachableRepository());

        assertThrows(AppSettingRepository.ReadFailed.class, limit::current);
    }

    @Test
    void theSettingsScreenStillReadsADatabaseFaultAsUnset() {
        assertTrue(over(unreachableRepository()).shown().isEmpty());
    }

    @Test
    void aStoredValueOutsideTheRangeIsNotUnset() {
        ReviewDiffLimit.Unreadable refused = assertThrows(ReviewDiffLimit.Unreadable.class,
                () -> over(holding("10")).current());

        assertTrue(refused.getMessage().contains("Settings -> General"), refused.getMessage());
    }

    @Test
    void aStoredValueThatIsNotANumberIsNotUnset() {
        assertThrows(ReviewDiffLimit.Unreadable.class, () -> over(holding("TEST-abc")).current());
    }

    @Test
    void theSettingsScreenShowsABadRowAsUnsetSoItCanBeSavedAgain() {
        assertTrue(over(holding("TEST-abc")).shown().isEmpty());
    }

    @Test
    void aStoredLimitIsRead() {
        assertEquals(Optional.of(60_000), over(holding("60000")).current());
        assertEquals(Optional.of(60_000), over(holding("60000")).shown());
    }

    @Test
    void noRowIsUnset() {
        assertTrue(over(holding(null)).current().isEmpty());
    }

    private static ReviewDiffLimit over(AppSettingRepository settings) {
        ReviewDiffLimit limit = new ReviewDiffLimit();
        limit.settings = settings;
        return limit;
    }

    /** The real repository, whose every connection attempt fails as a database that is down does. */
    private static AppSettingRepository unreachableRepository() {
        AppSettingRepository settings = new AppSettingRepository();
        settings.dataSource = (DataSource) Proxy.newProxyInstance(DataSource.class.getClassLoader(),
                new Class<?>[] {DataSource.class}, (proxy, method, args) -> {
                    throw new SQLException("TEST-unreachable");
                });
        return settings;
    }

    /** A repository holding {@code value} under the limit's key, or no row when null. */
    private static AppSettingRepository holding(String value) {
        return new AppSettingRepository() {
            @Override
            public Optional<String> getStrictly(String key) {
                return Optional.ofNullable(value);
            }

            @Override
            public Optional<String> get(String key) {
                return Optional.ofNullable(value);
            }
        };
    }
}
