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

    /**
     * A year. Not a policy judgement — a window is never usefully longer than this — but a bound on
     * arithmetic: {@code Instant.now().minus(window)} throws {@code DateTimeException} beyond the
     * instant range and {@code ArithmeticException} near {@code Long.MAX_VALUE}, and it throws from
     * inside {@code AttentionQueries.collect} (outside its SQLException catch, so the WHOLE panel goes
     * dark), from the pre-spend gate and from the conversation gate (both dead-lettering to cs.dlq).
     * Rejected at the REST boundary too; enforced here as well because a value stored before that
     * validation existed, or by any future writer, would otherwise leave the operator unable to clear it
     * through the product — {@code GET /api/settings/caps} constructs the Duration and 500s.
     */
    public static final long MAX_WINDOW_MINUTES = 525_600L;

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

    /** The rolling window length, defaulting to a day when unset, unparseable or out of range. */
    public Duration window() {
        return Duration.ofMinutes(readLong(KEY_WINDOW_MINUTES)
                .filter(minutes -> minutes <= MAX_WINDOW_MINUTES)
                .orElse(DEFAULT_WINDOW_MINUTES));
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

    /**
     * A stored value that is not a POSITIVE number reads as unset. Every one of these keys is a positive
     * quantity, so zero and negatives are as meaningless as "not-a-number" — and the difference between
     * treating them alike matters: a stored {@code "0"} spend cap makes
     * {@code usage.spentMillicents() >= 0} true for every review, forever, on every gate. One row, and
     * the deployment stops reviewing.
     *
     * <p>{@code CapSettingsResource} already rejects a zero, and it stays there so an operator gets a
     * 400 rather than a silent no-op. It is not enough on its own: it is the only writer <em>today</em>,
     * and this exact "safe by construction" reasoning was falsified in this repository by V30, which
     * created rateless models directly in SQL without passing through the registry that forbids them
     * (the comment recording it sits in {@code ConversationSaga}). A migration or a support UPDATE is
     * the same shape. A guard at the correct boundary is only a fix once the callers can no longer
     * produce the bad value.
     */
    private static Optional<Integer> parseInt(String raw) {
        try {
            int parsed = Integer.parseInt(raw.trim());
            return parsed > 0 ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }

    /** @see #parseInt(String) — same rule, and the window's own default covers it from here. */
    private static Optional<Long> parseLong(String raw) {
        try {
            long parsed = Long.parseLong(raw.trim());
            return parsed > 0 ? Optional.of(parsed) : Optional.empty();
        } catch (NumberFormatException notANumber) {
            return Optional.empty();
        }
    }
}
