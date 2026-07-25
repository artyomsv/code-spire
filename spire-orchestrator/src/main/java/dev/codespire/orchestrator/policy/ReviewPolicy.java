package dev.codespire.orchestrator.policy;

import dev.codespire.orchestrator.settings.AppSettingRepository;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

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
    public static final String OBSERVE = "observe";
    public static final String ACTIVE = "active";
    public static final int MIN_ATTEMPTS = 1;
    public static final int MAX_ATTEMPTS = 10;

    private static final Logger LOG = Logger.getLogger(ReviewPolicy.class);

    @Inject
    AppSettingRepository settings;

    /** Seed default (observe = safe first contact) — used only until the UI slider sets a stored value. */
    @ConfigProperty(name = "spire.review.mode", defaultValue = OBSERVE)
    String defaultMode;

    /** Seed default for the retry budget — used only until Settings stores a value. */
    @ConfigProperty(name = "spire.review.max-attempts", defaultValue = "3")
    int defaultMaxAttempts;

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
