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
    public static final String OBSERVE = "observe";
    public static final String ACTIVE = "active";

    private static final Logger LOG = Logger.getLogger(ReviewPolicy.class);

    @Inject
    AppSettingRepository settings;

    /** Seed default (observe = safe first contact) — used only until the UI slider sets a stored value. */
    @ConfigProperty(name = "spire.review.mode", defaultValue = OBSERVE)
    String defaultMode;

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

    /** Any value that is not exactly 'observe' is treated as 'active' (matches boot semantics). */
    static String normalize(String mode) {
        return OBSERVE.equalsIgnoreCase(mode == null ? "" : mode.trim())
                ? OBSERVE
                : ACTIVE;
    }
}
