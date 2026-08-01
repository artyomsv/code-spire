package dev.codespire.diff;

import dev.codespire.contract.scm.ChangeType;
import dev.codespire.contract.scm.DiffLine;
import dev.codespire.contract.scm.FilePatch;
import dev.codespire.contract.scm.Hunk;
import dev.codespire.contract.scm.LineType;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses unified diff text into the canonical {@link FilePatch} model, tracking BOTH old and
 * new line numbers per line — the property inline-comment anchoring depends on
 * (SCM-MAPPING.md). Hunk-header handling follows the same dual-numbering
 * approach PR-Agent demonstrates; see NOTICE.
 *
 * <p>Git-style input ({@code diff --git} headers, what Bitbucket Cloud, GitHub and GitLab return)
 * is the normal case. Plain {@code diff -u} output carries only {@code ---}/{@code +++} headers,
 * and is parsed by a fallback detector — see {@link #headerlessSectionAt}.
 */
public final class UnifiedDiffParser {

    private static final System.Logger LOG = System.getLogger(UnifiedDiffParser.class.getName());

    // @@ -oldStart[,oldCount] +newStart[,newCount] @@ [section]
    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@ ?(.*)");
    // Greedy on purpose; see splitDiffGitPaths for the " b/"-in-path handling.
    private static final Pattern DIFF_GIT = Pattern.compile("^diff --git a/(.+) b/(.+)$");
    private static final String DIFF_GIT_PREFIX = "diff --git ";
    private static final String OLD_PATH_PREFIX = "--- ";
    private static final String NEW_PATH_PREFIX = "+++ ";
    private static final String HUNK_PREFIX = "@@";
    /** Diff text quotes source; one header line is enough to identify a shape that failed to parse. */
    private static final int MAX_LOGGED_LINE = 120;

    private UnifiedDiffParser() {
    }

    /** Where one file's section begins, and the paths its header names. */
    private record Section(int headerStart, String oldPath, String newPath) {
    }

    /** How file sections are delimited in the diff at hand — chosen once per {@link #parse}. */
    @FunctionalInterface
    private interface SectionDetector {
        /** The section starting at {@code i}, or null when none starts there. */
        Section at(String[] lines, int i);
    }

    public static List<FilePatch> parse(String diffText) {
        List<FilePatch> patches = new ArrayList<>();
        if (diffText == null || diffText.isBlank()) {
            return patches;
        }

        String[] lines = diffText.split("\n", -1);
        collectSections(lines, detectorFor(lines), patches);
        warnIfNothingParsed(lines, patches);
        return patches;
    }

    /**
     * Git headers win whenever the text has any, so a hunk line that happens to read like a bare
     * {@code --- foo} header (removing a line whose own text starts with {@code -- }) can never be
     * mistaken for a file boundary. The fallback is reached only by input that carries no
     * {@code diff --git} line at all.
     */
    private static SectionDetector detectorFor(String[] lines) {
        for (String line : lines) {
            if (line.startsWith(DIFF_GIT_PREFIX)) {
                return UnifiedDiffParser::gitSectionAt;
            }
        }
        return UnifiedDiffParser::headerlessSectionAt;
    }

    private static void collectSections(String[] lines, SectionDetector detector, List<FilePatch> patches) {
        int i = 0;
        while (i < lines.length) {
            Section section = detector.at(lines, i);
            if (section == null) {
                i++;
                continue;
            }
            try {
                i = parseFile(lines, section, patches);
            } catch (RuntimeException e) {
                // one malformed file must not kill the whole PR parse
                LOG.log(System.Logger.Level.WARNING,
                        "Skipping malformed diff section for " + section.newPath(), e);
                i = skipToNextSection(lines, i + 1, detector);
            }
        }
    }

    /** A git-style section: the header block starts on the line after {@code diff --git}. */
    private static Section gitSectionAt(String[] lines, int i) {
        String[] paths = splitDiffGitPaths(lines[i]);
        return paths == null ? null : new Section(i + 1, paths[0], paths[1]);
    }

    /**
     * A bare {@code --- X} / {@code +++ Y} / {@code @@} triple — what plain {@code diff -u} emits,
     * and what several providers return per file before a header is re-attached.
     *
     * <p>Without this, such input matched nothing and {@code parse} returned an empty list with no
     * error: the review saw zero changed files and posted an empty summary. GitLab's compare
     * endpoint hit exactly that, and it stayed invisible for weeks because the same text read
     * correctly everywhere it was treated as prose.
     *
     * <p>The {@code @@} in the triple is what separates a real file header from two adjacent hunk
     * lines; {@link #detectorFor} keeps git-style input away from this path entirely.
     */
    private static Section headerlessSectionAt(String[] lines, int i) {
        if (i + 2 >= lines.length
                || !lines[i].startsWith(OLD_PATH_PREFIX)
                || !lines[i + 1].startsWith(NEW_PATH_PREFIX)
                || !lines[i + 2].startsWith(HUNK_PREFIX)) {
            return null;
        }
        String oldPath = stripPrefix(lines[i].substring(OLD_PATH_PREFIX.length()));
        String newPath = stripPrefix(lines[i + 1].substring(NEW_PATH_PREFIX.length()));
        if (oldPath == null || newPath == null) {
            return null;
        }
        return new Section(i, oldPath, newPath);
    }

    /**
     * A non-blank diff that yields no files is always an anomaly, and a silent one: every caller
     * just sees an empty change set, so the review proceeds with no findings and no error. The
     * fallback above makes this rare — the warning is what makes the next such shape cheap to find
     * instead of costing another multi-week hunt.
     */
    private static void warnIfNothingParsed(String[] lines, List<FilePatch> patches) {
        if (!patches.isEmpty()) {
            return;
        }
        String first = lines[0];
        LOG.log(System.Logger.Level.WARNING,
                "Diff text was not blank but parsed to zero files — the review will see no changes."
                        + " First line: "
                        + (first.length() <= MAX_LOGGED_LINE ? first
                                : first.substring(0, MAX_LOGGED_LINE) + "…"));
    }

    /**
     * Splits the "diff --git a/X b/Y" header into {oldPath, newPath}, or null
     * when the line is not such a header. Paths are identical except for
     * renames/copies, so when the remainder admits an equal split we prefer it —
     * the greedy regex mis-splits paths that themselves contain " b/" (this only
     * matters for binary files; text files get their paths from ---/+++ lines).
     */
    private static String[] splitDiffGitPaths(String line) {
        Matcher m = DIFF_GIT.matcher(line);
        if (!m.matches()) {
            return null;
        }
        String rest = line.substring(DIFF_GIT_PREFIX.length());
        int pathLength = (rest.length() - 5) / 2; // "a/" + path + " b/" + path
        if (rest.length() >= 7 && (rest.length() - 5) % 2 == 0) {
            String candidate = rest.substring(2, 2 + pathLength);
            if (rest.substring(2 + pathLength).equals(" b/" + candidate)) {
                return new String[]{candidate, candidate};
            }
        }
        return new String[]{m.group(1), m.group(2)};
    }

    /** Advances to the next line a section starts on (or the end of input). */
    private static int skipToNextSection(String[] lines, int from, SectionDetector detector) {
        int i = from;
        while (i < lines.length && detector.at(lines, i) == null) {
            i++;
        }
        return i;
    }

    /** Parses a hunk-header number defensively — absurd values are clamped, never abort the parse. */
    private static int parseHunkNumber(String digits, int fallback) {
        if (digits == null) {
            return fallback;
        }
        try {
            return (int) Math.min(Long.parseLong(digits), Integer.MAX_VALUE);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE; // more digits than a long — clamp, the line bound still ends the hunk
        }
    }

    /** Parses one file section from its header block; returns the next index. */
    private static int parseFile(String[] lines, Section section, List<FilePatch> patches) {
        String oldPath = section.oldPath();
        String newPath = section.newPath();
        ChangeType change = ChangeType.MODIFIED;
        boolean binary = false;

        int i = section.headerStart();
        // file header block until the first hunk or the next file
        while (i < lines.length && !lines[i].startsWith(HUNK_PREFIX) && !lines[i].startsWith(DIFF_GIT_PREFIX)) {
            String line = lines[i];
            if (line.startsWith("new file mode")) {
                change = ChangeType.ADDED;
            } else if (line.startsWith("deleted file mode")) {
                change = ChangeType.DELETED;
            } else if (line.startsWith("rename from ")) {
                oldPath = line.substring("rename from ".length());
                change = ChangeType.RENAMED;
            } else if (line.startsWith("rename to ")) {
                newPath = line.substring("rename to ".length());
            } else if (line.startsWith("copy to ")) {
                newPath = line.substring("copy to ".length());
                change = ChangeType.COPIED;
            } else if (line.startsWith("Binary files ") || line.startsWith("GIT binary patch")) {
                binary = true;
            } else if (line.startsWith(OLD_PATH_PREFIX)) {
                String p = stripPrefix(line.substring(OLD_PATH_PREFIX.length()));
                if (p != null) {
                    oldPath = p;
                }
            } else if (line.startsWith(NEW_PATH_PREFIX)) {
                String p = stripPrefix(line.substring(NEW_PATH_PREFIX.length()));
                if (p != null) {
                    newPath = p;
                }
            }
            i++;
        }

        List<Hunk> hunks = new ArrayList<>();
        while (i < lines.length && lines[i].startsWith(HUNK_PREFIX)) {
            Matcher m = HUNK_HEADER.matcher(lines[i]);
            if (!m.matches()) {
                // silently dropping the file's remaining hunks would hide real content
                LOG.log(System.Logger.Level.WARNING,
                        "Malformed hunk header in diff for " + newPath + " — dropping remaining hunks: " + lines[i]);
                break;
            }
            int oldStart = parseHunkNumber(m.group(1), 1);
            int oldCount = parseHunkNumber(m.group(2), 1);
            int newStart = parseHunkNumber(m.group(3), 1);
            int newCount = parseHunkNumber(m.group(4), 1);
            i++;

            List<DiffLine> hunkLines = new ArrayList<>();
            int oldLine = oldStart;
            int newLine = newStart;
            // The header counts bound the hunk — this is what keeps trailing
            // blank lines (or any noise after the last hunk) out of it.
            int oldRemaining = oldCount;
            int newRemaining = newCount;
            while (i < lines.length && (oldRemaining > 0 || newRemaining > 0)
                    && !lines[i].startsWith(HUNK_PREFIX) && !lines[i].startsWith(DIFF_GIT_PREFIX)) {
                String line = lines[i];
                if (line.startsWith("\\")) {
                    i++; // "\ No newline at end of file" — not a diff line
                    continue;
                }
                if (line.startsWith("+")) {
                    hunkLines.add(new DiffLine(LineType.ADDED, null, newLine++, line.substring(1)));
                    newRemaining--;
                } else if (line.startsWith("-")) {
                    hunkLines.add(new DiffLine(LineType.REMOVED, oldLine++, null, line.substring(1)));
                    oldRemaining--;
                } else if (line.startsWith(" ")) {
                    hunkLines.add(new DiffLine(LineType.CONTEXT, oldLine++, newLine++, line.substring(1)));
                    oldRemaining--;
                    newRemaining--;
                } else if (line.isEmpty() && oldRemaining > 0 && newRemaining > 0) {
                    // some generators emit empty context lines without the leading
                    // space — but a context line consumes one line on BOTH sides,
                    // so accept it only while the header still expects both;
                    // otherwise it's noise and treating it as context would drift
                    // every later anchor in the hunk
                    hunkLines.add(new DiffLine(LineType.CONTEXT, oldLine++, newLine++, ""));
                    oldRemaining--;
                    newRemaining--;
                } else {
                    break; // anything else ends the hunk
                }
                i++;
            }
            hunks.add(new Hunk(oldStart, oldCount, newStart, newCount, List.copyOf(hunkLines)));
        }

        String effectiveNew = "/dev/null".equals(newPath) ? null : newPath;
        String effectiveOld = "/dev/null".equals(oldPath) ? null : oldPath;
        if (effectiveNew == null) {
            change = ChangeType.DELETED;
        }
        if (effectiveOld == null) {
            change = ChangeType.ADDED;
        }
        patches.add(new FilePatch(
                effectiveOld,
                effectiveNew,
                change,
                Languages.of(effectiveNew != null ? effectiveNew : effectiveOld),
                binary,
                false,
                List.copyOf(hunks)));
        return i;
    }

    /** Strips the a/ b/ prefix from a ---/+++ header path; keeps /dev/null as-is. */
    private static String stripPrefix(String path) {
        if (path == null || path.isBlank()) {
            return null;
        }
        path = path.trim();
        // strip a trailing tab + timestamp some generators append
        int tab = path.indexOf('\t');
        if (tab > 0) {
            path = path.substring(0, tab);
        }
        if ("/dev/null".equals(path)) {
            return path;
        }
        if (path.startsWith("a/") || path.startsWith("b/")) {
            return path.substring(2);
        }
        return path;
    }
}
