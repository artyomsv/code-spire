package dev.codespire.orchestrator.settings;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.Optional;

/**
 * The Review diff limit (Settings → General): the most diff, in estimated tokens, an API review sends to the model.
 * Unset means "as much as the model's window allows". It caps the diff only, never the other prompt parts, and the
 * worker never lowers a diff below today's fixed budget for it (review input limit design, 2026-11-26).
 *
 * <p>Read fresh on every credential pack, so a change applies to the next review without a restart. Unset is the
 * expensive answer, so the pack reads it strictly ({@link #current}): a failed read or a stored value outside the range
 * fails the pack and is retried, rather than sending a review uncapped (PR 456, security/M-1). The settings screen
 * reads it leniently ({@link #shown}), so a bad row can still be seen and replaced.
 */
@ApplicationScoped
public class ReviewDiffLimit {

    private static final Logger LOG = Logger.getLogger(ReviewDiffLimit.class);

    /** {@code app_setting} key. No row means unset. */
    public static final String KEY = "review.diff-limit-tokens";

    /** Today's REVIEW diff budget: a lower limit would not lower anything, so it is refused rather than ignored. */
    public static final int MIN_TOKENS = 24_000;

    /** Above every context window in the catalog today; a typo of an extra zero is refused rather than sent. */
    public static final int MAX_TOKENS = 2_000_000;

    @Inject
    AppSettingRepository settings;

    /**
     * The limit a review is packed with, or empty when no row is stored.
     *
     * @throws AppSettingRepository.ReadFailed when the database read fails
     * @throws Unreadable when the stored value is not a whole number in range
     */
    public Optional<Integer> current() {
        Optional<String> stored = settings.getStrictly(KEY);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        Integer parsed = parse(stored.orElseThrow());
        if (parsed == null) {
            throw new Unreadable(stored.orElseThrow());
        }
        return Optional.of(parsed);
    }

    /** The limit for the settings screen: a read failure or a stored value outside the range shows as unset. */
    public Optional<Integer> shown() {
        Optional<String> stored = settings.get(KEY);
        Integer parsed = stored.map(ReviewDiffLimit::parse).orElse(null);
        if (stored.isPresent() && parsed == null) {
            LOG.warnf("app_setting %s holds '%s', which is not a whole number from %d to %d; reviews are not packed "
                    + "until it is saved again in Settings -> General", KEY, stored.orElseThrow(), MIN_TOKENS, MAX_TOKENS);
        }
        return Optional.ofNullable(parsed);
    }

    /** Stores the limit, or clears it with {@code null}. Validate with {@link #isAccepted} first. */
    public void set(Integer tokens) {
        if (tokens == null) {
            settings.delete(KEY);
            return;
        }
        if (!isAccepted(tokens)) {
            throw new IllegalArgumentException("Review diff limit must be a whole number from " + MIN_TOKENS
                    + " to " + MAX_TOKENS + ", got " + tokens);
        }
        settings.set(KEY, Integer.toString(tokens));
    }

    public static boolean isAccepted(long tokens) {
        return tokens >= MIN_TOKENS && tokens <= MAX_TOKENS;
    }

    private static Integer parse(String raw) {
        try {
            long value = Long.parseLong(raw.strip());
            return isAccepted(value) ? (int) value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** A stored limit no review may be packed with: reading it as unset would lift the operator's cap. */
    public static final class Unreadable extends IllegalStateException {

        Unreadable(String stored) {
            super("app_setting " + KEY + " holds '" + stored + "', which is not a whole number from " + MIN_TOKENS
                    + " to " + MAX_TOKENS + ". No review is sent until it is fixed: save the Review diff limit again "
                    + "in Settings -> General (blank for no limit), or delete the row.");
        }
    }
}
