package dev.codespire.diff;

import dev.codespire.contract.scm.DiffLine;
import dev.codespire.contract.scm.FilePatch;
import dev.codespire.contract.scm.Hunk;
import dev.codespire.contract.scm.InlineAnchor;
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

    public static Optional<InlineAnchor> resolveNewLine(List<FilePatch> patches, String path, int newLine) {
        for (FilePatch patch : patches) {
            if (!path.equals(patch.newPath())) {
                continue;
            }
            for (Hunk hunk : patch.hunks()) {
                for (DiffLine line : hunk.lines()) {
                    if (line.newLine() != null && line.newLine() == newLine) {
                        String srcPath = patch.oldPath() != null ? patch.oldPath() : patch.newPath();
                        return Optional.of(new InlineAnchor(
                                patch.newPath(), srcPath, line.oldLine(), line.newLine(), Side.NEW));
                    }
                }
            }
        }
        return Optional.empty();
    }
}
