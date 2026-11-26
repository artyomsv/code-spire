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
 * PR 456: unset is the expensive answer for the Review diff limit, so the credential pack reads it strictly. A
 * database fault throws rather than read as unset (security/M-1); a stored value outside the range reads as the lowest
 * limit, never as unset and never as a block on reviews (code-quality/M-5). The settings screen tells an invalid row
 * apart from no row, so a blank save can clear it. No container: a real repository over a database that is down, and
 * a repository holding a given row.
 */
class ReviewDiffLimitReadTest {

    @Test
    void aDatabaseFaultIsNotUnset() {
        ReviewDiffLimit limit = over(unreachableRepository());

        assertThrows(AppSettingRepository.ReadFailed.class, limit::current);
    }

    @Test
    void theSettingsScreenStillReadsADatabaseFaultAsUnset() {
        assertEquals(new ReviewDiffLimit.Shown(null, false), over(unreachableRepository()).shown());
    }

    @Test
    void aStoredValueOutsideTheRangeReadsAsTheLowestLimit() {
        assertEquals(Optional.of(ReviewDiffLimit.MIN_TOKENS), over(holding("10")).current());
    }

    @Test
    void aStoredValueThatIsNotANumberReadsAsTheLowestLimit() {
        assertEquals(Optional.of(ReviewDiffLimit.MIN_TOKENS), over(holding("TEST-abc")).current());
    }

    @Test
    void theSettingsScreenFlagsABadRowSoItCanBeCleared() {
        assertEquals(new ReviewDiffLimit.Shown(null, true), over(holding("TEST-abc")).shown());
    }

    @Test
    void aLongBadValueIsQuotedOnlyInPart() {
        String excerpt = ReviewDiffLimit.excerpt("TEST-" + "x".repeat(500));

        assertTrue(excerpt.length() <= ReviewDiffLimit.LOGGED_VALUE_CHARS + 3, excerpt);
    }

    @Test
    void aStoredLimitIsRead() {
        assertEquals(Optional.of(60_000), over(holding("60000")).current());
        assertEquals(new ReviewDiffLimit.Shown(60_000, false), over(holding("60000")).shown());
    }

    @Test
    void noRowIsUnset() {
        assertTrue(over(holding(null)).current().isEmpty());
        assertEquals(new ReviewDiffLimit.Shown(null, false), over(holding(null)).shown());
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
    static AppSettingRepository holding(String value) {
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
