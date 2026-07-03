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
 * Parses git-style unified diff text (what Bitbucket Cloud, GitHub, and GitLab
 * return) into the canonical {@link FilePatch} model, tracking BOTH old and
 * new line numbers per line — the property inline-comment anchoring depends on
 * (SCM-MAPPING.md). Hunk-header semantics ported from pr-agent's
 * git_patch_processing (Apache-2.0).
 */
public final class UnifiedDiffParser {

    // @@ -oldStart[,oldCount] +newStart[,newCount] @@ [section]
    private static final Pattern HUNK_HEADER =
            Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@ ?(.*)");
    private static final Pattern DIFF_GIT = Pattern.compile("^diff --git a/(.+) b/(.+)$");

    private UnifiedDiffParser() {
    }

    public static List<FilePatch> parse(String diffText) {
        List<FilePatch> patches = new ArrayList<>();
        if (diffText == null || diffText.isBlank()) {
            return patches;
        }

        String[] lines = diffText.split("\n", -1);
        int i = 0;
        while (i < lines.length) {
            Matcher header = DIFF_GIT.matcher(lines[i]);
            if (!header.matches()) {
                i++;
                continue;
            }
            i = parseFile(lines, i, header.group(1), header.group(2), patches);
        }
        return patches;
    }

    /** Parses one file section starting at the "diff --git" line; returns the next index. */
    private static int parseFile(String[] lines, int start, String aPath, String bPath,
                                 List<FilePatch> patches) {
        String oldPath = aPath;
        String newPath = bPath;
        ChangeType change = ChangeType.MODIFIED;
        boolean binary = false;

        int i = start + 1;
        // file header block until the first hunk or the next file
        while (i < lines.length && !lines[i].startsWith("@@") && !lines[i].startsWith("diff --git ")) {
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
            } else if (line.startsWith("--- ")) {
                String p = stripPrefix(line.substring(4));
                if (p != null) {
                    oldPath = p;
                }
            } else if (line.startsWith("+++ ")) {
                String p = stripPrefix(line.substring(4));
                if (p != null) {
                    newPath = p;
                }
            }
            i++;
        }

        List<Hunk> hunks = new ArrayList<>();
        while (i < lines.length && lines[i].startsWith("@@")) {
            Matcher m = HUNK_HEADER.matcher(lines[i]);
            if (!m.matches()) {
                break;
            }
            int oldStart = Integer.parseInt(m.group(1));
            int oldCount = m.group(2) != null ? Integer.parseInt(m.group(2)) : 1;
            int newStart = Integer.parseInt(m.group(3));
            int newCount = m.group(4) != null ? Integer.parseInt(m.group(4)) : 1;
            i++;

            List<DiffLine> hunkLines = new ArrayList<>();
            int oldLine = oldStart;
            int newLine = newStart;
            // The header counts bound the hunk — this is what keeps trailing
            // blank lines (or any noise after the last hunk) out of it.
            int oldRemaining = oldCount;
            int newRemaining = newCount;
            while (i < lines.length && (oldRemaining > 0 || newRemaining > 0)
                    && !lines[i].startsWith("@@") && !lines[i].startsWith("diff --git ")) {
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
                } else if (line.startsWith(" ") || line.isEmpty()) {
                    // some generators emit empty context lines without the leading space
                    hunkLines.add(new DiffLine(LineType.CONTEXT, oldLine++, newLine++, line.isEmpty() ? "" : line.substring(1)));
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
