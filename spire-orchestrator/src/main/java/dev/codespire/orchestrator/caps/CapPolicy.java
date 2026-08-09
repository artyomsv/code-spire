package dev.codespire.orchestrator.caps;

import dev.codespire.orchestrator.settings.AppSettingRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;

/**
 * The configured spend limits, read fresh from {@code app_setting} on every call (the pattern
 * {@code ReviewPolicy} already uses) so a Settings change takes effect without a restart.
 *
 * <p><b>Unset means unlimited, and unset is the default.</b> A deployment that configures nothing
 * must behave exactly as it does today. Shipping a non-null default would silently change a running
 * deployment's behaviour on upgrade -- the mistake V30 made by leaving legacy models rateless, still
 * the most operator-visible consequence of ADR-023.
 *
 * <p>An unparseable stored value is also treated as unset -- fail open. A corrupt setting must not
 * refuse every review; the operator can see the field is wrong in Settings, whereas a deployment that
 * silently stops reviewing looks like an outage.
 *
 * <p>The window is the one exception: it always has an effective value (a day), because a rolling
 * window with no length is not a lesser cap, it is a meaningless one.
 */
@ApplicationScoped
public class CapPolicy {

    public static final String KEY_MAX_CHANGED_FILES = "caps.max-changed-files";
    public static final String KEY_MAX_DIFF_BYTES = "caps.max-diff-bytes";
    public static final String KEY_SPEND_CAP = "caps.spend-millicents";
    public static final String KEY_CALLS = "caps.calls";
    public static final String KEY_WINDOW_MINUTES = "caps.window-minutes";

    /** A day: long enough to see a spend pattern, short enough that a raised cap takes effect same-day. */
    static final long DEFAULT_WINDOW_MINUTES = 1440L;

    @Inject
    AppSettingRepository settings;

    public OptionalInt maxChangedFiles() {
        return toOptionalInt(readInt(KEY_MAX_CHANGED_FILES));
    }

    public OptionalLong maxDiffBytes() {
        return toOptionalLong(readLong(KEY_MAX_DIFF_BYTES));
    }

    public OptionalLong spendCapMillicents() {
        return toOptionalLong(readLong(KEY_SPEND_CAP));
    }

    public OptionalInt callCap() {
        return toOptionalInt(readInt(KEY_CALLS));
    }

    /** The rolling window length, defaulting to a day when unset or unparseable. */
    public Duration window() {
        return Duration.ofMinutes(readLong(KEY_WINDOW_MINUTES).orElse(DEFAULT_WINDOW_MINUTES));
    }

    private Optional<Integer> readInt(String key) {
        return settings.get(key).flatMap(CapPolicy::parseInt);
    }

    private Optional<Long> readLong(String key) {
        return settings.get(key).flatMap(CapPolicy::parseLong);
    }

    private static OptionalInt toOptionalInt(Optional<Integer> parsed) {
        return parsed.map(OptionalInt::of).orElseGet(OptionalInt::empty);
    }

    private static OptionalLong toOptionalLong(Optional<Long> parsed) {
        return parsed.map(OptionalLong::of).orElseGet(OptionalLong::empty);
    }

    private static Optional<Integer> parseInt(String raw) {
        try {
            return Optional.of(Integer.parseInt(raw.trim()));
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    private static Optional<Long> parseLong(String raw) {
        try {
            return Optional.of(Long.parseLong(raw.trim()));
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }
}
