package dev.codespire.context.jira;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Jira issue-key extraction and project-key filtering — shared by the worker
 * (candidate extraction at diff-fetch) and the orchestrator (the on-demand
 * preview). One regex, one home.
 *
 * <p>{@link #candidates} is deliberately permissive (a false match like {@code UTF-8}
 * simply 404s downstream, so we favor recall); {@link #filter} narrows to the
 * provider's configured project keys when set, which is where precision comes from.
 */
public final class JiraTicketKeys {

    // Prefix up to 15 chars so long project keys (e.g. ACME) are captured; over-capture is
    // harmless — configured project keys filter it, and an unmatched key just 404s.
    private static final Pattern KEY = Pattern.compile("\\b[A-Z][A-Z0-9]{1,14}-\\d+\\b");
    private static final Pattern BARE_NUMBER = Pattern.compile("^\\d+$");
    private static final int MAX_KEYS = 10;

    private JiraTicketKeys() {
    }

    /** Candidate issue keys parsed from the given sources (title/branch/description), capped. */
    public static Set<String> candidates(String... sources) {
        Set<String> keys = new LinkedHashSet<>();
        for (String source : sources) {
            if (source == null || source.isBlank()) {
                continue;
            }
            Matcher m = KEY.matcher(source);
            while (m.find() && keys.size() < MAX_KEYS) {
                keys.add(m.group());
            }
            if (keys.size() >= MAX_KEYS) {
                break;
            }
        }
        return keys;
    }

    /** Parse the operator's configured project-key list ("ACME, PROJ") into normalized keys. */
    public static Set<String> parseProjectKeys(String raw) {
        Set<String> keys = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return keys;
        }
        for (String token : raw.split("[,\\s]+")) {
            String key = token.trim().toUpperCase();
            if (!key.isBlank()) {
                keys.add(key);
            }
        }
        return keys;
    }

    /** Keep only keys whose project prefix is in {@code projectKeys}; when that is empty, keep all. */
    public static Set<String> filter(Set<String> keys, Set<String> projectKeys) {
        if (projectKeys == null || projectKeys.isEmpty()) {
            return keys;
        }
        Set<String> matched = new LinkedHashSet<>();
        for (String key : keys) {
            if (projectKeys.contains(projectOf(key))) {
                matched.add(key);
            }
        }
        return matched;
    }

    /**
     * Resolve preview input into issue keys: any full keys in the text, plus — when the input is a bare
     * number and project keys are configured — {@code <projectKey>-<number>} for each configured project.
     */
    public static Set<String> resolvePreview(String text, Set<String> projectKeys) {
        Set<String> keys = filter(candidates(text), projectKeys);
        if (keys.isEmpty() && text != null && BARE_NUMBER.matcher(text.trim()).matches()) {
            for (String project : projectKeys) {
                keys.add(project + "-" + text.trim());
            }
        }
        return keys;
    }

    private static String projectOf(String key) {
        int dash = key.lastIndexOf('-');
        return dash < 0 ? key : key.substring(0, dash);
    }
}
