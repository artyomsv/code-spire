package dev.codespire.orchestrator.policy;

import dev.codespire.orchestrator.settings.AppSettingRepository;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import java.time.Duration;

/**
 * The global review mode — first-contact safety. {@code observe} registers each PR
 * (persisted and shown on the dashboard) but emits NO action commands: no diff
 * fetch, no LLM call, no comments. {@code active} runs the full pipeline. The
 * per-provider author allowlist lives in the provider registry, not here.
 *
 * <p>The mode is stored in {@code app_setting} and read fresh on every event, so
 * the Settings slider flips it WITHOUT a restart — that stored value is the sole
 * live control. The seed default is {@code observe} (first-contact safety: a
 * fresh database posts nothing until an operator flips the slider to active).
 */
@ApplicationScoped
public class ReviewPolicy {

    /** {@code app_setting} key holding the review mode. */
    public static final String MODE_KEY = "review.mode";
    /** {@code app_setting} key holding the review pipeline's retry budget. */
    public static final String MAX_ATTEMPTS_KEY = "review.max-attempts";
    /** {@code app_setting} keys holding the retry backoff. */
    public static final String BACKOFF_BASE_KEY = "review.backoff-base-ms";
    public static final String BACKOFF_FACTOR_KEY = "review.backoff-factor";
    public static final String OBSERVE = "observe";
    public static final String ACTIVE = "active";
    public static final int MIN_ATTEMPTS = 1;
    public static final int MAX_ATTEMPTS = 10;
    /** Five minutes: long enough to sit out a provider incident, short enough that a stuck review is
     *  noticed. The conversation path caps at a minute because a human is waiting on that reply. */
    public static final long MAX_BACKOFF_MS = 300_000L;
    public static final double MIN_BACKOFF_FACTOR = 1d;
    public static final double MAX_BACKOFF_FACTOR = 10d;

    private static final Logger LOG = Logger.getLogger(ReviewPolicy.class);

    @Inject
    AppSettingRepository settings;

    /** Seed default (observe = safe first contact) — used only until the UI slider sets a stored value. */
    @ConfigProperty(name = "spire.review.mode", defaultValue = OBSERVE)
    String defaultMode;

    /** Seed default for the retry budget — used only until Settings stores a value. */
    @ConfigProperty(name = "spire.review.max-attempts", defaultValue = "3")
    int defaultMaxAttempts;

    /** Seed defaults for the backoff. 5s doubling is aimed at a provider incident rather than a blip —
     *  the immediate re-dispatch this replaced burned every attempt inside ten seconds. */
    @ConfigProperty(name = "spire.review.backoff-base-ms", defaultValue = "5000")
    long defaultBackoffBaseMs;

    @ConfigProperty(name = "spire.review.backoff-factor", defaultValue = "2.0")
    double defaultBackoffFactor;

    // Eager (observes StartupEvent, fired after Flyway) so the posture is visible at boot.
    void onStart(@Observes StartupEvent ev) {
        LOG.infof("Review policy: mode=%s (stored=%s, seed default=%s)",
                observeOnly() ? "OBSERVE (register only, no diff/LLM/comments)" : "active",
                settings.get(MODE_KEY).orElse("<unset>"), normalize(defaultMode));
    }

    /** The effective mode right now — the stored override, else the seed default. */
    public String currentMode() {
        return normalize(settings.get(MODE_KEY).orElse(defaultMode));
    }

    /** True when a run must be registered but emit no action commands. */
    public boolean observeOnly() {
        return OBSERVE.equals(currentMode());
    }

    /** Persist a new mode; the next event picks it up (no restart). */
    public void setMode(String mode) {
        String m = normalize(mode);
        settings.set(MODE_KEY, m);
        LOG.infof("Review mode set to %s", m);
    }

    /**
     * How many times a review pipeline phase is attempted before it fails terminally (ADR-016). Stored
     * like the mode, so Settings changes it without a restart; the config property is the seed default.
     *
     * <p>Distinct from the CONVERSATION retry budget: a review that exhausts this ends as a failed
     * review carrying the provider's error, while a follow-up answer dead-letters for replay.
     */
    public int maxAttempts() {
        return settings.get(MAX_ATTEMPTS_KEY)
                .map(stored -> clampAttempts(parseOr(stored, defaultMaxAttempts)))
                .orElseGet(() -> clampAttempts(defaultMaxAttempts));
    }

    /** Persist a new attempt budget; the next failure picks it up (no restart). */
    public void setMaxAttempts(int attempts) {
        int clamped = clampAttempts(attempts);
        settings.set(MAX_ATTEMPTS_KEY, Integer.toString(clamped));
        LOG.infof("Review retry attempts set to %d", clamped);
    }

    /** One attempt means "never retry"; the ceiling keeps a typo from parking a review on a dead
     *  provider for hours (each retry re-runs the whole pipeline from the diff fetch). */
    static int clampAttempts(int attempts) {
        return Math.max(MIN_ATTEMPTS, Math.min(MAX_ATTEMPTS, attempts));
    }

    /** Wait before the second attempt; later attempts multiply it by {@link #backoffFactor()}. */
    public long backoffBaseMs() {
        return settings.get(BACKOFF_BASE_KEY)
                .map(stored -> clampBackoffBase(parseLongOr(stored, defaultBackoffBaseMs)))
                .orElseGet(() -> clampBackoffBase(defaultBackoffBaseMs));
    }

    /** Multiplier applied to the wait on each further attempt. */
    public double backoffFactor() {
        return settings.get(BACKOFF_FACTOR_KEY)
                .map(stored -> clampBackoffFactor(parseDoubleOr(stored, defaultBackoffFactor)))
                .orElseGet(() -> clampBackoffFactor(defaultBackoffFactor));
    }

    /** Persist the backoff; the next failure picks it up (no restart). */
    public void setBackoff(long baseMs, double factor) {
        settings.set(BACKOFF_BASE_KEY, Long.toString(clampBackoffBase(baseMs)));
        settings.set(BACKOFF_FACTOR_KEY, Double.toString(clampBackoffFactor(factor)));
        LOG.infof("Review retry backoff set to base=%dms factor=%s", clampBackoffBase(baseMs),
                clampBackoffFactor(factor));
    }

    /**
     * How long to wait before {@code attempt} (2 for the first retry): {@code base * factor^(attempt-2)},
     * capped. The cap is generous compared with the conversation path's minute, because nobody is waiting
     * on a review retry — whereas a human IS waiting on an answer to their comment.
     */
    public Duration retryDelay(int attempt) {
        double raw = backoffBaseMs() * Math.pow(backoffFactor(), Math.max(0, attempt - 2));
        return Duration.ofMillis((long) Math.min(MAX_BACKOFF_MS, Math.max(0d, raw)));
    }

    static long clampBackoffBase(long baseMs) {
        return Math.max(0L, Math.min(MAX_BACKOFF_MS, baseMs));
    }

    static double clampBackoffFactor(double factor) {
        return Math.max(MIN_BACKOFF_FACTOR, Math.min(MAX_BACKOFF_FACTOR, factor));
    }

    private static long parseLongOr(String value, long fallback) {
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private static double parseDoubleOr(String value, double fallback) {
        try {
            return Double.parseDouble(value.trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private static int parseOr(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    /** Any value that is not exactly 'observe' is treated as 'active' (matches boot semantics). */
    static String normalize(String mode) {
        return OBSERVE.equalsIgnoreCase(mode == null ? "" : mode.trim())
                ? OBSERVE
                : ACTIVE;
    }
}
