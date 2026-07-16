package dev.codespire.orchestrator.provider;

import dev.codespire.contract.review.ConversationLevel;
import dev.codespire.orchestrator.settings.AppSettingRepository;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Resolves the effective conversation level (spec §2) and the conversational-loop tuning knobs
 * (turn cap, retry attempts, backoff) from {@code app_setting} — runtime-configurable, absent → code
 * default, clamped on both read and write so a bad value can never wedge the loop.
 */
@ApplicationScoped
public class ConversationLevels {

    static final String GLOBAL_KEY = "conversation.level";
    static final String TURN_CAP_KEY = "conversation.turn-cap";
    static final String MAX_ATTEMPTS_KEY = "conversation.max-attempts";
    static final String BACKOFF_BASE_MS_KEY = "conversation.backoff-base-ms";
    static final String BACKOFF_FACTOR_KEY = "conversation.backoff-factor";

    @Inject
    ProviderRegistry providers;

    @Inject
    AppSettingRepository settings;

    /** The global default (app_setting 'conversation.level'); absent → REPORT_ONLY (fail-safe: no conversation). */
    public ConversationLevel globalDefault() {
        return ConversationLevel.parse(settings.get(GLOBAL_KEY).orElse(null));
    }

    public void setGlobalDefault(ConversationLevel level) {
        settings.set(GLOBAL_KEY, level.name());
    }

    /** Max bot replies per thread before deferring to the team (spec §8). Tuning knob; safe default. */
    public int turnCap() {
        return clampInt(settings.get(TURN_CAP_KEY), 4, 1, 50);
    }

    public void setTurnCap(int value) {
        settings.set(TURN_CAP_KEY, String.valueOf(clamp(value, 1, 50)));
    }

    /** In-worker attempts on a transient SCM/LLM failure before the command is dead-lettered to cs.dlq. */
    public int maxAttempts() {
        return clampInt(settings.get(MAX_ATTEMPTS_KEY), 5, 1, 10);
    }

    public void setMaxAttempts(int value) {
        settings.set(MAX_ATTEMPTS_KEY, String.valueOf(clamp(value, 1, 10)));
    }

    /** First backoff wait (ms) before a retried attempt. */
    public long backoffBaseMs() {
        return clampLong(settings.get(BACKOFF_BASE_MS_KEY), 2000, 100, 60000);
    }

    public void setBackoffBaseMs(long value) {
        settings.set(BACKOFF_BASE_MS_KEY, String.valueOf(clamp(value, 100, 60000)));
    }

    /** Exponential backoff factor applied per retried attempt. */
    public double backoffFactor() {
        return clampDouble(settings.get(BACKOFF_FACTOR_KEY), 2.0, 1.0, 5.0);
    }

    public void setBackoffFactor(double value) {
        settings.set(BACKOFF_FACTOR_KEY, String.valueOf(clamp(value, 1.0, 5.0)));
    }

    /** Parses defensively (unparseable → default) then clamps to [min, max]. */
    static int clampInt(Optional<String> raw, int defaultValue, int min, int max) {
        int value = defaultValue;
        if (raw.isPresent()) {
            try {
                value = Integer.parseInt(raw.get().trim());
            } catch (NumberFormatException ignored) {
                value = defaultValue;
            }
        }
        return clamp(value, min, max);
    }

    static long clampLong(Optional<String> raw, long defaultValue, long min, long max) {
        long value = defaultValue;
        if (raw.isPresent()) {
            try {
                value = Long.parseLong(raw.get().trim());
            } catch (NumberFormatException ignored) {
                value = defaultValue;
            }
        }
        return clamp(value, min, max);
    }

    static double clampDouble(Optional<String> raw, double defaultValue, double min, double max) {
        double value = defaultValue;
        if (raw.isPresent()) {
            try {
                value = Double.parseDouble(raw.get().trim());
            } catch (NumberFormatException ignored) {
                value = defaultValue;
            }
        }
        return clamp(value, min, max);
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static long clamp(long value, long min, long max) {
        return Math.max(min, Math.min(max, value));
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    /** The provider's override if set, else the global default. */
    public ConversationLevel effectiveLevel(String type, String workspace) {
        ConversationLevel globalDefault = globalDefault();
        return providers.resolve(type, workspace)
                .map(p -> effective(p.conversationLevel(), globalDefault))
                .orElse(globalDefault);
    }

    /** Pure decision: a non-blank provider value wins; otherwise the global default. */
    static ConversationLevel effective(String providerRaw, ConversationLevel globalDefault) {
        return (providerRaw == null || providerRaw.isBlank())
                ? globalDefault
                : ConversationLevel.parse(providerRaw);
    }
}
