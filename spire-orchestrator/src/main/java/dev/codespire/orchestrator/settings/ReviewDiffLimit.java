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
 * expensive answer, so the pack reads it strictly ({@link #current}): a failed read throws, and the review saga turns
 * it into a scheduled retry rather than sending a review uncapped (PR 456, security/M-1, code-quality/M-4). A stored
 * value outside the range never blocks a review: it reads as {@link #MIN_TOKENS}, the conservative bound, so every
 * kind keeps today's fixed budget, and an attention row and the settings screen say so until it is saved again.
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
     * The limit a review is packed with, or empty when no row is stored. An invalid stored value reads as
     * {@link #MIN_TOKENS}, never as unset: unset would lift the operator's cap.
     *
     * @throws AppSettingRepository.ReadFailed when the database read fails
     */
    public Optional<Integer> current() {
        Optional<String> stored = settings.getStrictly(KEY);
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
