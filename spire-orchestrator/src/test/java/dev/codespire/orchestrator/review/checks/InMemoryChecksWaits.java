package dev.codespire.orchestrator.review.checks;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The wait table in memory, with the real one's take semantics: only a waiting row on the named head can be taken, and
 * only once. Every method the gate, the resolver and the outcomes reach is overridden; the real ones open a database.
 */
class InMemoryChecksWaits extends ChecksWaitStore {

    private static final String WAITING = "waiting";
    private static final String TIMED_OUT = "timed_out";

    private record Row(ChecksWait wait, String state) {
    }

    private final Map<String, Row> rows = new LinkedHashMap<>();
    /** What a sweep claims; the tests decide which waits are due. */
    final List<ChecksWait> due = new ArrayList<>();

    @Override
    public void startWaiting(ChecksWait wait) {
        rows.put(wait.reviewId(), new Row(wait, WAITING));
    }

    @Override
    public void clear(String reviewId) {
        rows.remove(reviewId);
    }

    @Override
    public boolean take(String reviewId, String head) {
        if (!isWaitingOn(reviewId, head)) return false;
        rows.remove(reviewId);
        return true;
    }

    @Override
    public boolean markTimedOut(String reviewId, String head) {
        if (!isWaitingOn(reviewId, head)) return false;
        rows.put(reviewId, new Row(rows.get(reviewId).wait(), TIMED_OUT));
        return true;
    }

    @Override
    public boolean startedUnfinished(String reviewId, String commit) {
        Row row = rows.get(reviewId);
        return row != null && TIMED_OUT.equals(row.state()) && row.wait().head().equals(commit);
    }

    @Override
    public boolean isWaiting(String reviewId) {
        Row row = rows.get(reviewId);
        return row != null && WAITING.equals(row.state());
    }

    @Override
    public List<ChecksWait> waitingOn(UUID repositoryId, String head) {
        return rows.values().stream()
                .filter(row -> WAITING.equals(row.state()) && row.wait().head().equals(head)
                        && row.wait().repositoryId().equals(repositoryId))
                .map(Row::wait).toList();
    }

    @Override
    public List<ChecksWait> claimDue(int limit) {
        List<ChecksWait> claimed = List.copyOf(due);
        due.clear();
        return claimed;
    }

    /** The review's wait, or null. */
    ChecksWait waitOf(String reviewId) {
        Row row = rows.get(reviewId);
        return row == null ? null : row.wait();
    }

    private boolean isWaitingOn(String reviewId, String head) {
        Row row = rows.get(reviewId);
        return row != null && WAITING.equals(row.state()) && row.wait().head().equals(head);
    }
}
