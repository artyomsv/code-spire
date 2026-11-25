package dev.codespire.orchestrator.review.checks;

import dev.codespire.orchestrator.review.checks.ChecksWait.Stage;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The admission table in memory, with the real one's guards: registration never replaces a row of the same or a later
 * round, a claim moves only a waiting row of the same round, every later change names the round and the stage it
 * observed, and a completed outcome stays in its terminal stage. Every method the gate, the resolver and the outcomes
 * reach is overridden; the real ones open a database. The sweep claims every waiting or claimed row, and an admitted
 * one once its grace has passed.
 */
class InMemoryChecksWaits extends ChecksWaitStore {

    private static final Set<Stage> DUE = Set.of(Stage.WAITING, Stage.STARTING, Stage.STARTING_UNFINISHED, Stage.SKIPPING);
    private static final Set<Stage> WAITING = Set.of(Stage.ADMITTED, Stage.WAITING);
    private final Map<String, ChecksWait> rows = new LinkedHashMap<>();
    /** The rounds a review was shown waiting for, in order. */
    final List<Integer> shownWaiting = new ArrayList<>();

    @Override
    public void register(ChecksWait wait) {
        ChecksWait row = rows.get(wait.reviewId());
        if (row == null || row.round() < wait.round()) rows.put(wait.reviewId(), wait);
    }

    @Override
    public boolean ready(ChecksWait.Run run) {
        ChecksWait row = rows.get(run.reviewId());
        if (row == null || row.round() != run.round() || !row.head().equals(run.head()) || row.stage() != Stage.ADMITTED) {
            return false;
        }
        rows.put(run.reviewId(), row.at(Stage.WAITING));
        headersStarted.add(run.round());
        shownWaiting.add(run.round());
        return true;
    }

    @Override
    public int admittedRound(String reviewId) {
        ChecksWait row = rows.get(reviewId);
        return row == null ? 0 : row.round();
    }

    @Override
    public boolean markWaiting(String reviewId, int round) {
        ChecksWait row = rows.get(reviewId);
        boolean waiting = row != null && row.round() == round && row.stage() == Stage.WAITING;
        if (waiting) shownWaiting.add(round);
        return waiting;
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
        Stage done = switch (claimed.stage()) {
            case STARTING_UNFINISHED -> Stage.TIMED_OUT;
            case SKIPPING -> Stage.SKIPPED;
            default -> Stage.STARTED;
        };
        rows.put(claimed.reviewId(), row.at(done));
    }

    @Override
    public void drop(ChecksWait observed) {
        ChecksWait row = rows.get(observed.reviewId());
        if (row != null && row.round() == observed.round() && row.stage() == observed.stage()) {
            rows.remove(observed.reviewId());
        }
    }

    @Override
    public boolean startedUnfinished(ChecksWait.Run run) {
        ChecksWait row = rows.get(run.reviewId());
        return row != null && row.head().equals(run.head()) && row.round() == run.round()
                && (row.stage() == Stage.STARTING_UNFINISHED || row.stage() == Stage.TIMED_OUT);
    }

    @Override
    public boolean isWaiting(String reviewId) {
        ChecksWait row = rows.get(reviewId);
        return row != null && WAITING.contains(row.stage());
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
        return rows.values().stream().filter(row -> DUE.contains(row.stage()) || graceOver(row)).toList();
    }

    /** An admitted row is due once its grace has passed, as next_read_at is in the real table. */
    private static boolean graceOver(ChecksWait row) {
        return row.stage() == Stage.ADMITTED
                && !java.time.Instant.now().isBefore(row.waitingSince().plus(ChecksWaitDecision.GRACE));
    }

    /** The runs whose header was set up as they were made ready, by round. */
    final List<Integer> headersStarted = new ArrayList<>();

    /** Ages the review's row, as time passing would. */
    void age(String reviewId, java.time.Duration by) {
        ChecksWait row = rows.get(reviewId);
        rows.put(reviewId, new ChecksWait(row.reviewId(), row.repositoryId(), row.head(), row.round(), row.untrusted(),
                row.waitingSince().minus(by), row.stage(), row.failedChecks()));
    }

    /** The review's row, or null. */
    ChecksWait rowOf(String reviewId) {
        return rows.get(reviewId);
    }
}
