package dev.codespire.context.confluence;

import java.net.URI;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Confluence link extraction and page-id resolution — the link-driven analog of
 * {@code JiraTicketKeys}. The worker extracts candidate URLs from the PR
 * title/branch/description at diff-fetch (recall-favoring — a non-Confluence URL is
 * simply dropped by {@link #pageIds}); the provider then narrows those to the
 * configured Confluence host and pulls the numeric page id out of each.
 *
 * <p>Two Confluence URL shapes carry a page id directly: the Cloud/DC pretty URL
 * ({@code .../pages/123456/Title}) and the DC view action
 * ({@code .../pages/viewpage.action?pageId=123456}). Tiny links ({@code /x/AbCd})
 * and title-only URLs ({@code /display/SPACE/Title}) carry no id and are skipped —
 * resolving them would need extra round-trips for marginal gain.
 */
public final class ConfluenceLinks {

    private static final Pattern URL = Pattern.compile("https?://[^\\s<>\"')]+");
    private static final Pattern PAGES_PATH = Pattern.compile("/pages/(\\d+)");
    private static final Pattern PAGE_ID_PARAM = Pattern.compile("[?&]pageId=(\\d+)");
    private static final Pattern BARE_NUMBER = Pattern.compile("^\\d+$");
    private static final int MAX_LINKS = 10;

    private ConfluenceLinks() {
    }

    /** Candidate URLs parsed from the given sources (title/branch/description), trimmed and capped. */
    public static Set<String> candidates(String... sources) {
        Set<String> links = new LinkedHashSet<>();
        for (String source : sources) {
            if (source == null || source.isBlank()) {
                continue;
            }
            Matcher m = URL.matcher(source);
            while (m.find() && links.size() < MAX_LINKS) {
                links.add(trimTrailingPunctuation(m.group()));
            }
            if (links.size() >= MAX_LINKS) {
                break;
            }
        }
        return links;
    }

    /** Parse the operator's configured space-key list ("ENG, DOC") into normalized upper-case keys. */
    public static Set<String> parseSpaceKeys(String raw) {
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

    /**
     * The page ids referenced by {@code links} that point at the configured Confluence host. A link on
     * another host, or one without a resolvable page id, is dropped. Order-preserving and deduplicated.
     */
    public static Set<String> pageIds(java.util.Collection<String> links, String baseUrl) {
        Set<String> ids = new LinkedHashSet<>();
        if (links == null || links.isEmpty()) {
            return ids;
        }
        String baseHost = hostOf(baseUrl);
        for (String link : links) {
            if (baseHost != null && !baseHost.equalsIgnoreCase(hostOf(link))) {
                continue;
            }
            pageId(link).ifPresent(ids::add);
        }
        return ids;
    }

    /** The numeric page id embedded in a Confluence URL, or empty when the URL carries none. */
    public static java.util.Optional<String> pageId(String url) {
        if (url == null) {
            return java.util.Optional.empty();
        }
        Matcher param = PAGE_ID_PARAM.matcher(url);
        if (param.find()) {
            return java.util.Optional.of(param.group(1));
        }
        Matcher path = PAGES_PATH.matcher(url);
        if (path.find()) {
            return java.util.Optional.of(path.group(1));
        }
        return java.util.Optional.empty();
    }

    /**
     * Resolve preview input into page ids: any page ids in a pasted Confluence URL, or — when the input
     * is a bare number — that number treated as a page id directly.
     */
    public static Set<String> resolvePreview(String text, String baseUrl) {
        Set<String> ids = pageIds(candidates(text), baseUrl);
        if (ids.isEmpty() && text != null && BARE_NUMBER.matcher(text.trim()).matches()) {
            ids.add(text.trim());
        }
        return ids;
    }

    private static String hostOf(String url) {
        try {
            return URI.create(url).getHost();
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Markdown/prose often trails a URL with punctuation (")", ".", ","); strip it so the host parses. */
    private static String trimTrailingPunctuation(String url) {
        int end = url.length();
        while (end > 0 && ").,;]".indexOf(url.charAt(end - 1)) >= 0) {
            end--;
        }
        return url.substring(0, end);
    }
}
