package dev.codespire.context.gitlab;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitLab reference extraction and parsing — the counterpart to {@code JiraTicketKeys}, shared by the
 * worker (extraction at diff-fetch) and the orchestrator (the preview). One grammar, one home, no I/O.
 *
 * <p>GitLab spells three different objects with three sigils: {@code #12} an issue, {@code !34} a
 * merge request, {@code &7} an epic. Each is relative to the project (or, for an epic, the project's
 * ancestor group) unless the reference qualifies itself. Namespaces nest, so the qualified form takes
 * one or more slashes — unlike GitHub, where {@code owner/repo} is the whole namespace.
 *
 * <p>Extraction favours recall, as the SPI intends: a wrong candidate costs one 404 the provider
 * skips, and {@link #MAX_REFS} bounds the cost.
 */
public final class GitLabIssueRefs {

    /** What a sigil refers to. Each resolves through a different API path. */
    public enum Kind {
        ISSUE, MERGE_REQUEST, EPIC
    }

    private static final Pattern BARE_ISSUE = Pattern.compile("(?<![\\w/&!-])#(\\d{1,7})\\b");
    private static final Pattern BARE_MERGE_REQUEST = Pattern.compile("(?<![\\w/#&-])!(\\d{1,7})\\b");
    private static final Pattern BARE_EPIC = Pattern.compile("(?<![\\w/#!-])&(\\d{1,7})\\b");

    /**
     * One or more slashes: GitLab namespaces nest arbitrarily deep.
     *
     * <p>Not preceded by {@code /}, for the same reason as the GitHub adapter's equivalent: without the
     * guard, {@code http://x/y#3} yields the false candidate {@code x/y#3}. A leading {@code :} stays
     * allowed so {@code ref:acme/proj#12} extracts.
     */
    private static final Pattern QUALIFIED_ISSUE =
            Pattern.compile("(?<!/)\\b((?:[A-Za-z0-9_.-]+/)+[A-Za-z0-9_.-]+)#(\\d{1,7})\\b");

    // Matched on the path, so gitlab.com and a self-managed host share one pattern each.
    private static final Pattern URL_ISSUE =
            Pattern.compile("https?://[^\\s<>\"')]*?/((?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+)/-/issues/(\\d{1,7})\\b");
    private static final Pattern URL_MERGE_REQUEST = Pattern.compile(
            "https?://[^\\s<>\"')]*?/((?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+)/-/merge_requests/(\\d{1,7})\\b");
    private static final Pattern URL_EPIC = Pattern.compile(
            "https?://[^\\s<>\"')]*?/groups/((?:[A-Za-z0-9_.-]+/)*[A-Za-z0-9_.-]+)/-/epics/(\\d{1,7})\\b");

    private static final int MAX_REFS = 10;

    private GitLabIssueRefs() {
    }

    /**
     * One reference. {@code projectPath} is null for a bare form, meaning "the project this review
     * runs in" — which only the provider can supply, and only once it knows the review is on GitLab.
     * For an epic URL it is the group named in the URL.
     */
    public record Ref(Kind kind, String projectPath, int number) {

        public boolean isProjectRelative() {
            return projectPath == null;
        }
    }

    /** Candidate references in the given texts, capped. */
    public static Set<String> candidates(String... texts) {
        Set<String> found = new LinkedHashSet<>();
        for (String text : texts) {
            if (text == null || text.isBlank()) {
                continue;
            }
            // URLs and the qualified form first: both contain a sigil the bare patterns must not re-claim.
            collect(URL_EPIC.matcher(text), found);
            collect(URL_MERGE_REQUEST.matcher(text), found);
            collect(URL_ISSUE.matcher(text), found);
            collect(QUALIFIED_ISSUE.matcher(text), found);
            collect(BARE_ISSUE.matcher(text), found);
            collect(BARE_MERGE_REQUEST.matcher(text), found);
            collect(BARE_EPIC.matcher(text), found);
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

    /** The reference as kind, project/group and number, or empty when it is another source's. */
    public static Optional<Ref> parse(String reference) {
        if (reference == null || reference.isBlank()) {
            return Optional.empty();
        }
        String text = reference.strip();
        Optional<Ref> url = parseUrl(text);
        if (url.isPresent()) {
            return url;
        }
        Matcher qualified = QUALIFIED_ISSUE.matcher(text);
        if (qualified.find()) {
            return Optional.of(new Ref(Kind.ISSUE, qualified.group(1),
                    Integer.parseInt(qualified.group(2))));
        }
        Matcher issue = BARE_ISSUE.matcher(text);
        if (issue.find()) {
            return Optional.of(new Ref(Kind.ISSUE, null, Integer.parseInt(issue.group(1))));
        }
        Matcher mergeRequest = BARE_MERGE_REQUEST.matcher(text);
        if (mergeRequest.find()) {
            return Optional.of(new Ref(Kind.MERGE_REQUEST, null,
                    Integer.parseInt(mergeRequest.group(1))));
        }
        Matcher epic = BARE_EPIC.matcher(text);
        if (epic.find()) {
            return Optional.of(new Ref(Kind.EPIC, null, Integer.parseInt(epic.group(1))));
        }
        return Optional.empty();
    }

    private static Optional<Ref> parseUrl(String text) {
        Matcher epic = URL_EPIC.matcher(text);
        if (epic.find()) {
            return Optional.of(new Ref(Kind.EPIC, epic.group(1), Integer.parseInt(epic.group(2))));
        }
        Matcher mergeRequest = URL_MERGE_REQUEST.matcher(text);
        if (mergeRequest.find()) {
            return Optional.of(new Ref(Kind.MERGE_REQUEST, mergeRequest.group(1),
                    Integer.parseInt(mergeRequest.group(2))));
        }
        Matcher issue = URL_ISSUE.matcher(text);
        if (issue.find()) {
            return Optional.of(new Ref(Kind.ISSUE, issue.group(1), Integer.parseInt(issue.group(2))));
        }
        return Optional.empty();
    }

    /** Comparison form, so two spellings of one reference do not each start a retrieval round. */
    public static String normalize(String reference) {
        if (reference == null) {
            return "";
        }
        String text = reference.strip().toLowerCase(Locale.ROOT);
        return text.endsWith("/") ? text.substring(0, text.length() - 1) : text;
    }

    /**
     * The groups that could own an epic referenced from this project, nearest ancestor first. GitLab
     * epics live at group level and a project path does not say which ancestor owns them, so the
     * provider tries these in order.
     */
    public static List<String> ancestorGroups(String projectPath) {
        List<String> groups = new ArrayList<>();
        if (projectPath == null || projectPath.isBlank()) {
            return groups;
        }
        String path = projectPath;
        int slash = path.lastIndexOf('/');
        while (slash > 0) {
            path = path.substring(0, slash);
            groups.add(path);
            slash = path.lastIndexOf('/');
        }
        return groups;
    }

    /** Parse the operator's optional allow-list ("acme, acme/widgets") into normalized entries. */
    public static Set<String> parseProjectAllowList(String raw) {
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
     * Whether this project is in scope. An empty allow-list accepts everything on the configured
     * host. An entry matches the project exactly, or any project beneath it as a group prefix —
     * {@code acme} covers {@code acme/tools/widgets}.
     */
    public static boolean allows(Set<String> allowList, String projectPath) {
        if (allowList == null || allowList.isEmpty()) {
            return true;
        }
        if (projectPath == null) {
            return false;
        }
        String path = projectPath.toLowerCase(Locale.ROOT);
        for (String entry : allowList) {
            if (path.equals(entry) || path.startsWith(entry + "/")) {
                return true;
            }
        }
        return false;
    }
}
