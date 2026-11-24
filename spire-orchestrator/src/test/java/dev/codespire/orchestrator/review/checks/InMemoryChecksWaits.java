package dev.codespire.orchestrator.review.checks;

import dev.codespire.orchestrator.review.checks.ChecksWait.Stage;
import dev.codespire.orchestrator.review.checks.ChecksWaitStore.PendingAdmission;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The wait table in memory, with the real one's guards: a claim moves only a waiting row of the same round, and every
 * later change names the round, so it never touches a newer run's row. Every method the gate, the resolver and the
 * outcomes reach is overridden; the real ones open a database. The sweep claims every row but the timed-out hold.
 */
class InMemoryChecksWaits extends ChecksWaitStore {

    private final Map<String, ChecksWait> rows = new LinkedHashMap<>();

    @Override
    public void startWaiting(ChecksWait wait) {
        ChecksWait row = rows.get(wait.reviewId());
        if (row == null || row.round() < wait.round()) rows.put(wait.reviewId(), wait.at(Stage.WAITING));
    }

    @Override
    public void clearBefore(String reviewId, int round) {
        ChecksWait row = rows.get(reviewId);
        if (row != null && row.round() < round) rows.remove(reviewId);
    }

    @Override
    public boolean claim(ChecksWait claimed) {
        ChecksWait row = rows.get(claimed.reviewId());
        if (row == null || row.round() != claimed.round() || row.stage() != Stage.WAITING) return false;
        rows.put(claimed.reviewId(), claimed);
        return true;
    }

    @Override
    public void finish(ChecksWait claimed) {
        ChecksWait row = rows.get(claimed.reviewId());
        if (row == null || row.round() != claimed.round() || row.stage() != claimed.stage()) return;
        if (claimed.stage() == Stage.STARTING_UNFINISHED) rows.put(claimed.reviewId(), row.at(Stage.TIMED_OUT));
        else rows.remove(claimed.reviewId());
    }

    @Override
    public void drop(ChecksWait observed) {
        ChecksWait row = rows.get(observed.reviewId());
        if (row != null && row.round() == observed.round() && row.stage() == observed.stage()) {
            rows.remove(observed.reviewId());
        }
    }

    @Override
    public boolean startedUnfinished(String reviewId, String commit) {
        ChecksWait row = rows.get(reviewId);
        return row != null && row.head().equals(commit)
                && (row.stage() == Stage.STARTING_UNFINISHED || row.stage() == Stage.TIMED_OUT);
    }

    @Override
    public boolean isWaiting(String reviewId) {
        ChecksWait row = rows.get(reviewId);
        return row != null && row.stage() == Stage.WAITING;
    }

    @Override
    public List<ChecksWait> waitingOn(UUID repositoryId, String head) {
        return rows.values().stream()
                .filter(row -> row.stage() == Stage.WAITING && row.head().equals(head)
                        && row.repositoryId().equals(repositoryId))
                .toList();
    }

    @Override
    public List<ChecksWait> claimDue(int limit) {
        return rows.values().stream().filter(row -> row.stage() != Stage.TIMED_OUT).toList();
    }

    /** Admissions whose wait is not recorded; the tests name them. */
    final List<PendingAdmission> pending = new java.util.ArrayList<>();

    @Override
    public List<PendingAdmission> pendingAdmissions(int limit) {
        List<PendingAdmission> taken = pending.stream()
                .filter(admission -> !rows.containsKey(admission.reviewId())).toList();
        pending.clear();
        return taken;
    }

    /** The review's row, or null. */
    ChecksWait rowOf(String reviewId) {
        return rows.get(reviewId);
    }
}
