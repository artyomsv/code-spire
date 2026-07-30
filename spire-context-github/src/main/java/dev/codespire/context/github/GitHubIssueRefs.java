package dev.codespire.context.github;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub issue-reference extraction and parsing — the counterpart to {@code JiraTicketKeys}, shared
 * by the worker (candidate extraction at diff-fetch) and the orchestrator (the on-demand preview).
 * One grammar, one home, no I/O.
 *
 * <p>Three forms carry a reference: the bare {@code #123}, which is relative to the repository the
 * review runs in; the qualified {@code owner/repo#123}; and a full issue or pull-request URL. The
 * bare form is by far the most common and the only one that needs to know which platform the review
 * is on, which is why {@link Ref#isRepoRelative()} exists.
 *
 * <p>Extraction favours recall, as the SPI intends: a wrong candidate costs one 404 the provider
 * skips. A CSS colour such as {@code #123456} therefore matches and costs one 404 — distinguishing
 * colours from issue numbers by content is not reliably possible, and {@link #MAX_REFS} bounds it.
 */
public final class GitHubIssueRefs {

    /**
     * Not preceded by a word character, '/' or '-', so an anchor ({@code page#3}), a path fragment
     * and a hyphenated token do not read as references — while {@code fixes #3} and {@code (#3)} do.
     */
    private static final Pattern BARE = Pattern.compile("(?<![\\w/-])#(\\d{1,7})\\b");

    /**
     * Exactly one slash: {@code owner/repo} is the whole of a GitHub namespace.
     *
     * <p>Not preceded by {@code /}, because a URL fragment is not a reference: without the guard,
     * {@code http://x/y#3} yields the false candidate {@code x/y#3}. The URL pattern above claims real
     * issue and pull-request links first, so anything reaching here with a slash in front of it is a
     * path or an anchor. A leading {@code :} is deliberately still allowed, so {@code ref:acme/repo#12}
     * extracts — narrowing that too would cost recall the design does not want to lose.
     */
    private static final Pattern QUALIFIED =
            Pattern.compile("(?<!/)\\b([A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+)#(\\d{1,7})\\b");

    /**
     * Matched on the path, not the host, so github.com and a GitHub Enterprise host are covered by
     * one pattern. Pull requests share the issues id space and are resolved through the same call.
     */
    private static final Pattern URL = Pattern.compile(
            "https?://[^\\s<>\"')]*?/([A-Za-z0-9_.-]+)/([A-Za-z0-9_.-]+)/(?:issues|pull)/(\\d{1,7})\\b");

    /** Cost guard: a description listing dozens of issues must not become dozens of API calls. */
    private static final int MAX_REFS = 10;

    private GitHubIssueRefs() {
    }

    /**
     * One reference. {@code owner}/{@code repo} are null for the bare form, meaning "the repository
     * this review runs in" — which only the provider can supply, and only once it knows the review is
     * on GitHub.
     */
    public record Ref(String owner, String repo, int number) {

        public boolean isRepoRelative() {
            return owner == null || repo == null;
        }
    }

    /** Candidate references in the given texts (title/branch/description or retrieved bodies), capped. */
    public static Set<String> candidates(String... texts) {
        Set<String> found = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            // URL and qualified first: both contain a '#' or a path the bare pattern must not re-claim.
            collect(URL.matcher(text), found);
            collect(QUALIFIED.matcher(text), found);
            collect(BARE.matcher(text), found);
            if (found.size() >= MAX_REFS) {
                break;
            }
        }
        return found;
    }

    private static void collect(Matcher matcher, Set<String> into) {
        while (matcher.find() && into.size() < MAX_REFS) {
            into.add(matcher.group());
        }
    }

    /** The reference as a repository plus number, or empty when the string is another source's. */
    public static Optional<Ref> parse(String reference) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        String text = reference.strip();
        Matcher url = URL.matcher(text);
        if (url.find()) {
            return Optional.of(new Ref(url.group(1), url.group(2), Integer.parseInt(url.group(3))));
        }
        Matcher qualified = QUALIFIED.matcher(text);
        if (qualified.find()) {
            String[] parts = qualified.group(1).split("/");
            return Optional.of(new Ref(parts[0], parts[1], Integer.parseInt(qualified.group(2))));
        }
        Matcher bare = BARE.matcher(text);
        if (bare.find()) {
            return Optional.of(new Ref(null, null, Integer.parseInt(bare.group(1))));
        }
        return Optional.empty();
    }

    /**
     * Comparison form, so two spellings of one reference do not each start a retrieval round.
     * Repository names are case-insensitive on GitHub; a trailing slash is noise.
     */
    public static String normalize(String reference) {
        if (reference == null) {
            return "";
        }
        String text = reference.strip().toLowerCase(Locale.ROOT);
        return text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
    }

    /** Parse the operator's optional allow-list ("acme, acme/widgets") into normalized entries. */
    public static Set<String> parseRepoAllowList(String raw) {
        Set<String> entries = new LinkedHashSet<>();
        if (raw == null || raw.isBlank()) {
            return entries;
        }
        for (String token : raw.split("[,\\s]+")) {
            String entry = token.strip().toLowerCase(Locale.ROOT);
            if (!entry.isBlank()) {
                entries.add(entry);
            }
        }
        return entries;
    }

    /**
     * Whether this repository is in scope. An empty allow-list accepts everything on the configured
     * host — the generic behaviour, matching Jira's empty project-key list. An entry is either an
     * owner ({@code acme}, any repository under it) or an exact {@code owner/repo}.
     */
    public static boolean allows(Set<String> allowList, String owner, String repo) {
        if (allowList == null || allowList.isEmpty()) {
            return true;
        }
        if (owner == null) {
            return false;
        }
        String lowerOwner = owner.toLowerCase(Locale.ROOT);
        if (allowList.contains(lowerOwner)) {
            return true;
        }
        return repo != null && allowList.contains(lowerOwner + "/" + repo.toLowerCase(Locale.ROOT));
    }
}
