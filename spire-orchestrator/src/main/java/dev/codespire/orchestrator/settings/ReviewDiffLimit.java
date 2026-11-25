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
 * <p>Read fresh on every credential pack, so a change applies to the next review without a restart.
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

    /** The limit, or empty when unset. A stored value outside the range reads as unset, and says so. */
    public Optional<Integer> current() {
        Optional<String> stored = settings.get(KEY);
        if (stored.isEmpty()) {
            return Optional.empty();
        }
        Integer parsed = parse(stored.orElseThrow());
        if (parsed == null) {
            LOG.warnf("app_setting %s holds '%s', which is not a whole number from %d to %d — treating it as unset",
                    KEY, stored.orElseThrow(), MIN_TOKENS, MAX_TOKENS);
            return Optional.empty();
        }
        return Optional.of(parsed);
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
}
