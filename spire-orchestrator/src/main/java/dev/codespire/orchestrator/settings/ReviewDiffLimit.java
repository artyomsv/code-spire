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
 * expensive answer, so the pack reads it strictly ({@link #current}) and never takes a fault for unset. A failed read
 * and a stored value outside the range both read as {@link #MIN_TOKENS}, the documented conservative bound: a
 * 24,000-token diff cap (with a known window a RECONCILE may get up to 24,000, more than its old 12,000). The review
 * goes on, the operator's cap is never lifted, and a WARN says so (PR 456, security/M-1, code-quality/M-4). An invalid
 * row also raises an attention row and is flagged on the settings screen until it is saved again; a failed read raises
 * none, since the attention row is derived from the same database that just failed to answer.
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

    /** How much of an invalid stored value a log line quotes: enough to recognise it, never a whole pasted blob. */
    static final int LOGGED_VALUE_CHARS = 40;

    @Inject
    AppSettingRepository settings;

    /**
     * What the settings screen shows: the stored limit, or null when unset or invalid; {@code invalidStoredValue}
     * tells an invalid row from no row, so a blank Save can clear it (PR 456, code-quality/M-5).
     */
    public record Shown(Integer limitTokens, boolean invalidStoredValue) {
    }

    /**
     * The limit a review is packed with, or empty when no row is stored. A failed read and an invalid stored value
     * read as {@link #MIN_TOKENS}, never as unset: unset would lift the operator's cap.
     */
    public Optional<Integer> current() {
        Optional<String> stored;
        try {
            stored = settings.getStrictly(KEY);
        } catch (AppSettingRepository.ReadFailed e) {
            LOG.warnf(e.getCause(), "app_setting %s could not be read; this review is packed with %d",
                    KEY, MIN_TOKENS);
            return Optional.of(MIN_TOKENS);
        }
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        Integer parsed = parse(stored.orElseThrow());
        if (parsed == null) {
            LOG.warnf("app_setting %s holds '%s', which is not a whole number from %d to %d; reviews are packed with "
                    + "%d until it is saved again in Settings -> General", KEY, excerpt(stored.orElseThrow()),
                    MIN_TOKENS, MAX_TOKENS, MIN_TOKENS);
            return Optional.of(MIN_TOKENS);
        }
        return Optional.of(parsed);
    }

    /** The limit for the settings screen. A read failure shows as unset; an invalid row is flagged as such. */
    public Shown shown() {
        Optional<String> stored = settings.get(KEY);
        Integer parsed = stored.map(ReviewDiffLimit::parse).orElse(null);
        return new Shown(parsed, stored.isPresent() && parsed == null);
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

    static String excerpt(String stored) {
        return stored.length() <= LOGGED_VALUE_CHARS ? stored : stored.substring(0, LOGGED_VALUE_CHARS) + "...";
    }
}
