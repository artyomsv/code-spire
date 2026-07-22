package dev.codespire.diff;

import dev.codespire.contract.scm.DiffLine;
import dev.codespire.contract.scm.FilePatch;
import dev.codespire.contract.scm.Hunk;
import dev.codespire.contract.scm.InlineAnchor;
import dev.codespire.contract.scm.LineType;
import dev.codespire.contract.scm.Side;

import java.util.List;
import java.util.Optional;

/**
 * Maps a (path, NEW-side line) citation — what the model returns — onto a
 * validated {@link InlineAnchor}. A citation that doesn't land on a line
 * actually present in the diff yields empty: the finding is folded into the
 * summary instead of posting a detached/orphaned inline comment.
 */
public final class Anchors {

    private Anchors() {
    }

    /** Single-line NEW-side resolution — delegates to {@link #resolveLine} with a zero-width range. */
    public static Optional<InlineAnchor> resolveNewLine(List<FilePatch> patches, String path, int newLine) {
        return resolveLine(patches, path, newLine, newLine);
    }

    /**
     * NEW-side resolution wins (a finding on changed code anchors on the right side);
     * a pure deletion — the line exists only as REMOVED — anchors on the OLD side.
     * A NEW-side range extends to endLine only when the end resolves in the SAME hunk;
     * otherwise the anchor degrades to single-line. OLD-side anchors are single-line.
     */
    public static Optional<InlineAnchor> resolveLine(List<FilePatch> patches, String path,
                                                      int startLine, int endLine) {
        for (FilePatch patch : patches) {
            if (!path.equals(patch.newPath())) {
                continue;
            }
            String srcPath = patch.oldPath() != null ? patch.oldPath() : patch.newPath();
            for (Hunk hunk : patch.hunks()) {
                Optional<InlineAnchor> anchor = resolveInHunk(hunk, path, srcPath, startLine, endLine);
                if (anchor.isPresent()) {
                    return anchor;
                }
            }
        }
        return Optional.empty();
    }

    private static Optional<InlineAnchor> resolveInHunk(Hunk hunk, String path, String srcPath,
                                                         int startLine, int endLine) {
        DiffLine start = null;
        boolean endInHunk = false;
        DiffLine removedMatch = null;
        for (DiffLine line : hunk.lines()) {
            if (line.newLine() != null && line.newLine() == startLine) {
                start = line;
            }
            if (line.newLine() != null && line.newLine() == endLine) {
                endInHunk = true;
            }
            if (line.type() == LineType.REMOVED && line.oldLine() != null && line.oldLine() == startLine) {
                removedMatch = line;
            }
        }
        if (start != null) {
            Integer end = endLine > startLine && endInHunk ? endLine : null;
            return Optional.of(new InlineAnchor(path, srcPath, start.oldLine(), startLine, Side.NEW, end));
        }
        if (removedMatch != null) {
            return Optional.of(new InlineAnchor(path, srcPath, startLine, null, Side.OLD, null));
        }
        return Optional.empty();
    }
}
