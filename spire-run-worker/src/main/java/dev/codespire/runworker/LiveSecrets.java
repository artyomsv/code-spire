package dev.codespire.runworker;

import dev.codespire.secrets.SecretScrub;
import org.jboss.logging.Logger;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * The secrets of every run and sign-in this worker holds right now, for {@link SecretLogFilter}.
 *
 * <p>A failure detail was always scrubbed before it left the worker, but a log line was not: about
 * twenty-five places log an exception whose message can quote a container's create request, and with it
 * a model key, a forge token or a sign-in (review of PR #178). Scrubbing each site would miss the next
 * one written. So every log record passes one filter, and the filter scrubs against what is held here.
 *
 * <p>Static because Quarkus builds logging filters before CDI exists. An entry lives while its unit may
 * still produce output: from the start of a run until its unit is gone. A unit that outlives this
 * process is logged about by a later process that never decrypted its secrets, so that process cannot
 * scrub them — the same limit {@code RunFailures} states for the watchdog.
 */
final class LiveSecrets {

    private static final Logger LOG = Logger.getLogger(LiveSecrets.class);

    private static final Map<String, SecretScrub> HELD = new ConcurrentHashMap<>();

    private LiveSecrets() {
    }

    /**
     * Holds a run's secrets for the log filter. Never throws: building the scrub decrypts, and a run must
     * not fail because its log lines could not be protected — it says so instead.
     */
    static void register(String key, Supplier<SecretScrub> scrub) {
        try {
            HELD.put(key, scrub.get());
        } catch (RuntimeException unavailable) {
            LOG.warnf("%s: its secrets could not be read for log scrubbing (%s); its log lines are not scrubbed",
                    key, unavailable.getClass().getSimpleName());
        }
    }

    static void forget(String key) {
        HELD.remove(key);
    }

    static boolean isEmpty() {
        return HELD.isEmpty();
    }

    static boolean holds(String key) {
        return HELD.containsKey(key);
    }

    /** The text with every held secret replaced. */
    static String clean(String text) {
        if (text == null) return null;
        String cleaned = text;
        for (SecretScrub scrub : HELD.values()) cleaned = scrub.clean(cleaned);
        return cleaned;
    }
}
