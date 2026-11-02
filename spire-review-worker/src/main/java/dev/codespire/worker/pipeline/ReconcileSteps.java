package dev.codespire.worker.pipeline;

import dev.codespire.contract.port.CommentSink;
import dev.codespire.contract.port.DiffSource;
import dev.codespire.contract.port.ThreadSource;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.scm.ChangeType;
import dev.codespire.contract.scm.FilePatch;
import dev.codespire.contract.scm.Hunk;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.contract.scm.ThreadTranscript;
import dev.codespire.diff.UnifiedDiffParser;
import dev.codespire.llm.VerdictsParser;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The reconcile steps both review paths share (harness reviews, step 6): compare, remap renames, thread transcripts,
 * the verdict backstops and the anchor-collision filter. The API path's {@code ReviewWorker} and the harness path's
 * {@code HarnessReviewWorker} call the same code, so a harness re-review keeps the API path's deterministic backstops.
 */
final class ReconcileSteps {

    private static final Logger LOG = Logger.getLogger(ReconcileSteps.class);

    private ReconcileSteps() {
    }

    /** The pull request and the head a reconcile step reads. */
    record ReviewAt(RepoRef repo, long prId, String commit) {
    }

    /** The incremental diff (null: the full-diff fallback) and the prior findings remapped through its renames. */
    record Basis(String incrementalDiff, List<PriorFinding> remapped) {
    }

    /** Compare from the prior head, then follow each prior finding through the compare's renames, keeping its place. */
    static Basis basis(DiffSource source, ReviewAt at, PriorRun prior) {
        String incremental = compareOrNull(source, at, prior.headCommit());
        return new Basis(incremental, remapRenames(prior.findings(), renameMap(incremental)));
    }

    /**
     * @return the incremental diff, or null (fall back to the full diff) when the provider can't
     * compare or returns a blank result (GitLab: no diffs between the revisions; GitHub: an empty
     * body when base==head) — a blank string is not a valid "incremental diff", it's "no diff".
     */
    static String compareOrNull(DiffSource source, ReviewAt at, String priorHead) {
        String result;
        try {
            result = source.fetchCompareDiff(at.repo(), priorHead, at.commit());
        } catch (RuntimeException e) {
            LOG.infof("compare %s..%s unavailable (%s) — reconciling on the full diff",
                    priorHead, at.commit(), e.getMessage());
            return null;
        }
        if (result == null || result.isBlank()) {
            LOG.debugf("compare %s..%s returned a blank diff — reconciling on the full diff",
                    priorHead, at.commit());
            return null;
        }
        return result;
    }

    /** Best-effort per-finding thread transcripts: a fetch failure never aborts the reconcile. */
    static Map<String, ThreadTranscript> transcripts(CommentSink comments, ReviewAt at, List<PriorFinding> findings) {
        if (!(comments instanceof ThreadSource threadSource)) {
            return Map.of(); // transcripts are best-effort; reconcile still runs on findings + diff
        }
        Map<String, ThreadTranscript> transcripts = new HashMap<>();
        for (PriorFinding finding : findings) {
            if (finding.threadRef() == null) {
                continue;
            }
            try {
                transcripts.put(finding.threadRef(), threadSource.fetchThread(
                        at.repo(), at.prId(), new ThreadRef(finding.threadRef())));
            } catch (RuntimeException e) {
                LOG.debugf("thread %s fetch failed: %s", finding.threadRef(), e.getMessage());
            }
        }
        return transcripts;
    }

    /** The answer's verdicts against the numbered findings (an id outside them is dropped), then the backstop. */
    static List<FindingVerdict> judge(String answerText, List<PriorFinding> numbered, String incrementalDiff) {
        return downgradeUntouched(VerdictsParser.parse(answerText, numbered), incrementalDiff);
    }

    /**
     * Drops new findings whose anchor collides with a STILL_OPEN or UNCHANGED verdict — that code
     * is already tracked (in its thread, or deliberately left untouched) so a fresh finding there
     * would be a duplicate.
     */
    static ReviewResult dropAnchorCollisions(ReviewResult result, List<FindingVerdict> verdicts) {
        Set<String> stillPresent = verdicts.stream()
                .filter(v -> v.status() == FindingVerdict.Status.STILL_OPEN
                        || v.status() == FindingVerdict.Status.UNCHANGED)
                .map(v -> v.path() + ":" + v.line())
                .collect(Collectors.toSet());
        if (stillPresent.isEmpty()) {
            return result;
        }
        List<Finding> kept = result.findings().stream()
                .filter(f -> !stillPresent.contains(f.path() + ":" + f.range().startLine()))
                .toList();
        return result.withFindings(kept);
    }

    /** Old path -> new path for files the incremental diff renamed/moved; empty when there is no
     *  incremental diff (force-push fallback) or nothing was renamed. Copies are deliberately
     *  excluded (Minor fix): a COPIED patch's oldPath is the ORIGINAL file, still present and
     *  unmodified — remapping a prior finding there to the copy's path would follow it to code
     *  that never moved. */
    static Map<String, String> renameMap(String incrementalDiff) {
        if (incrementalDiff == null) {
            return Map.of();
        }
        Map<String, String> renames = new HashMap<>();
        for (FilePatch patch : UnifiedDiffParser.parse(incrementalDiff)) {
            if (patch.change() != ChangeType.RENAMED) {
                continue;
            }
            String oldPath = patch.oldPath();
            String newPath = patch.newPath();
            if (oldPath != null && !oldPath.isBlank() && newPath != null && !newPath.isBlank()
                    && !oldPath.equals(newPath)) {
                renames.put(oldPath, newPath);
            }
        }
        return renames;
    }

    /**
     * Follows each finding's path through {@code renames} — a no-op (same list) when nothing was
     * renamed, so first-review and non-rename follow-ups pay zero cost. A remapped finding keeps
     * its line/severity/message/threadRef; only the path changes.
     */
    static List<PriorFinding> remapRenames(List<PriorFinding> findings, Map<String, String> renames) {
        if (renames.isEmpty()) {
            return findings;
        }
        return findings.stream()
                .map(f -> {
                    String renamed = renames.get(f.path());
                    return renamed == null ? f
                            : new PriorFinding(renamed, f.line(), f.severity(), f.message(), f.threadRef());
                })
                .toList();
    }

    /**
     * Deterministic backstop: the LLM sometimes marks a finding STILL_OPEN even though the
     * follow-up commit never touched its own anchor line. When the incremental diff is available,
     * downgrade any such verdict to UNCHANGED so the reviewer stays silent on threads the
     * author's changes couldn't possibly have affected — hunk-level, not file-level, so fixing
     * one part of a file no longer keeps every other prior finding in that file "open". No
     * incremental diff (force-push/blank fallback) means we can't tell what was touched, so the
     * LLM's verdicts stand as-is.
     */
    static List<FindingVerdict> downgradeUntouched(List<FindingVerdict> verdicts, String incrementalDiff) {
        if (incrementalDiff == null) {
            return verdicts;
        }
        Map<String, List<int[]>> changedRangesByPath = changedOldSideRanges(incrementalDiff);
        return verdicts.stream()
                .map(v -> v.status() == FindingVerdict.Status.STILL_OPEN && !lineTouched(changedRangesByPath, v)
                        ? new FindingVerdict(v.threadRef(), v.path(), v.line(),
                                FindingVerdict.Status.UNCHANGED, v.note())
                        : v)
                .toList();
    }

    /** OLD-side hunk ranges per path (registered under both old and new path, rename-safe). */
    private static Map<String, List<int[]>> changedOldSideRanges(String incrementalDiff) {
        Map<String, List<int[]>> ranges = new HashMap<>();
        for (FilePatch patch : UnifiedDiffParser.parse(incrementalDiff)) {
            for (Hunk hunk : patch.hunks()) {
                int[] range = { hunk.oldStart(), hunk.oldStart() + Math.max(hunk.oldLines(), 1) - 1 };
                register(ranges, patch.oldPath(), range);
                register(ranges, patch.newPath(), range);
            }
        }
        return ranges;
    }

    private static void register(Map<String, List<int[]>> ranges, String path, int[] range) {
        if (path != null) {
            ranges.computeIfAbsent(path, k -> new ArrayList<>()).add(range);
        }
    }

    private static boolean lineTouched(Map<String, List<int[]>> ranges, FindingVerdict v) {
        List<int[]> forPath = ranges.get(v.path());
        if (forPath == null) {
            return false;
        }
        return forPath.stream().anyMatch(r -> v.line() >= r[0] && v.line() <= r[1]);
    }
}
