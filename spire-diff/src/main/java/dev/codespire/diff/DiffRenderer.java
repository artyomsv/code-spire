package dev.codespire.diff;

import dev.codespire.contract.scm.DiffLine;
import dev.codespire.contract.scm.FilePatch;
import dev.codespire.contract.scm.Hunk;

import java.util.List;

/**
 * Renders parsed patches into the numbered-hunk format the review prompt uses
 * — the "__new hunk__ / __old hunk__" contract ported from pr-agent. Line
 * numbers in the rendered text are what the model cites back, and they map 1:1
 * onto {@link dev.codespire.contract.scm.InlineAnchor} coordinates.
 */
public final class DiffRenderer {

    private DiffRenderer() {
    }

    public static String render(List<FilePatch> patches) {
        StringBuilder sb = new StringBuilder();
        for (FilePatch patch : patches) {
            if (patch.binary()) {
                continue;
            }
            sb.append("## File: '").append(displayPath(patch)).append("' (").append(patch.change()).append(")\n");
            for (Hunk hunk : patch.hunks()) {
                renderHunk(sb, hunk);
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    private static void renderHunk(StringBuilder sb, Hunk hunk) {
        boolean hasNew = hunk.lines().stream().anyMatch(l -> l.newLine() != null);
        boolean hasRemoved = hunk.lines().stream().anyMatch(l -> l.oldLine() != null && l.newLine() == null);

        if (hasNew) {
            sb.append("__new hunk__\n");
            for (DiffLine line : hunk.lines()) {
                if (line.newLine() == null) {
                    continue;
                }
                char sign = switch (line.type()) {
                    case ADDED -> '+';
                    default -> ' ';
                };
                sb.append(line.newLine()).append(' ').append(sign).append(line.content()).append('\n');
            }
        }
        if (hasRemoved) {
            sb.append("__old hunk__\n");
            for (DiffLine line : hunk.lines()) {
                if (line.oldLine() == null || line.newLine() != null) {
                    continue;
                }
                sb.append(line.oldLine()).append(" -").append(line.content()).append('\n');
            }
        }
    }

    private static String displayPath(FilePatch patch) {
        return patch.newPath() != null ? patch.newPath() : patch.oldPath();
    }
}
