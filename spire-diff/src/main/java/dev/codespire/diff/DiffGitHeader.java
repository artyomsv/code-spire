package dev.codespire.diff;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Reads the {@code diff --git a/X b/Y} header line into its old and new paths. */
final class DiffGitHeader {

    // Greedy on purpose; see paths for the " b/"-in-path handling.
    private static final Pattern DIFF_GIT = Pattern.compile("^diff --git a/(.+) b/(.+)$");
    static final String PREFIX = "diff --git ";

    private DiffGitHeader() {
    }

    /**
     * Splits the "diff --git a/X b/Y" header into {oldPath, newPath}, or null
     * when the line is not such a header. Paths are identical except for
     * renames/copies, so when the remainder admits an equal split we prefer it —
     * the greedy regex mis-splits paths that themselves contain " b/" (this only
     * matters for binary files; text files get their paths from ---/+++ lines).
     */
    static String[] paths(String line) {
        if (line.startsWith(PREFIX) && line.indexOf('"', PREFIX.length()) >= 0) {
            // Git C-quotes a path with a control, quote, backslash or non-ASCII byte; the regex reads neither form.
            String[] quoted = GitQuotedPath.splitQuotedPair(line.substring(PREFIX.length()));
            if (quoted != null) {
                return quoted;
            }
        }
        Matcher m = DIFF_GIT.matcher(line);
        if (!m.matches()) {
            return null;
        }
        String rest = line.substring(PREFIX.length());
        int pathLength = (rest.length() - 5) / 2; // "a/" + path + " b/" + path
        if (rest.length() >= 7 && (rest.length() - 5) % 2 == 0) {
            String candidate = rest.substring(2, 2 + pathLength);
            if (rest.substring(2 + pathLength).equals(" b/" + candidate)) {
                return new String[]{candidate, candidate};
            }
        }
        return new String[]{m.group(1), m.group(2)};
    }
}
