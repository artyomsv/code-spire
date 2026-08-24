package dev.codespire.orchestrator.readmodel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ArchivedNotice;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.orchestrator.attention.AttentionBroadcaster;
import dev.codespire.orchestrator.llm.ChargeCall;
import dev.codespire.orchestrator.llm.ChargeLine;
import io.quarkus.websockets.next.OpenConnections;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The reviews read model (DATA-MODEL §5) — the single writer to
 * {@code review_status} + {@code review_event}, and the source for GET
 * /api/reviews, GET /api/reviews/{workspace}/{slug}/{pr}, and the /ws/reviews
 * live feed. Projected from the sagas; rebuildable from the event streams.
 */
@ApplicationScoped
public class ReviewProjection {

    private static final Logger LOG = Logger.getLogger(ReviewProjection.class);
    private static final List<String> STEPS = List.of("Received", "Diff", "Context", "Review", "Comments", "Done");
    private static final String UNIQUE_VIOLATION = "23505";
    /** {@link ReviewDetail.FindingView#origin()} for a finding a human filed with {@code /finding};
     *  null is a finding the review produced, and is what every row stored before the field existed
     *  reads back as. Named once because it is now a value three methods agree on, one of which
     *  ({@link #unionConversationFindings}) decides what the dashboard shows by it. */
    static final String CONVERSATION_ORIGIN = "conversation";
    // Generous: each lost race costs one DB round trip, and N concurrent writers
    // can make one writer lose up to N-1 rounds in a row.
    private static final int SEQ_RETRY_LIMIT = 50;

    // Active-step index for the pipeline stepper (6 = every step done).
    public static final int STAGE_RECEIVED = 0;
    public static final int STAGE_DIFF = 1;
    public static final int STAGE_CONTEXT = 2;
    public static final int STAGE_REVIEW = 3;
    public static final int STAGE_COMMENTS = 4;
    public static final int STAGE_POSTING = 5;
    public static final int STAGE_DONE = 6;

    @Inject
    DataSource dataSource;

    @Inject
    ObjectMapper mapper;

    @Inject
    OpenConnections connections;

    @Inject
    EncryptionService encryption;

    /** No cycle: the broadcaster reads conditions through its own queries, never through here. */
    @Inject
    AttentionBroadcaster attention;

    // ---- writes (called by the sagas) --------------------------------------

    /**
     * Upsert the header of a review and reset its status/stage for a (re)run. Also resets
     * {@code answering} to false: the reviewId is stable per PR, so a fresh push re-enters this
     * upsert, and without the reset a stuck "responding…" flag (e.g. a follow-up that terminally
     * DLQs without ever posting {@code FollowUpPosted}) would otherwise bleed into the new,
     * unrelated run.
     */
    public void registerHeader(String reviewId, RepoRef repo, long prId, String title, String author,
                               String authorId, String sourceBranch, String destBranch, String sha,
                               String htmlUrl, String providerType, String status, int stage) {
        upsertHeader(reviewId, repo, prId, title, author, authorId, sourceBranch, destBranch, sha,
                htmlUrl, providerType, status, stage, true);
    }

    /**
     * Refresh a review's PR metadata WITHOUT claiming a run: status, stage, attempt, error detail and the
     * answering flag are left exactly as they were.
     *
     * <p>Used when an event arrives for a commit the aggregate has already reviewed (a re-delivered
     * webhook, a provider's "test" delivery). {@link #registerHeader} would overwrite the finished
     * outcome with {@code reviewing}, and since no run starts, nothing ever moves it on again — the
     * review sat in "reviewing" forever with no command on the bus.
     */
    public void refreshHeader(String reviewId, RepoRef repo, long prId, String title, String author,
                              String authorId, String sourceBranch, String destBranch, String sha,
                              String htmlUrl, String providerType) {
        upsertHeader(reviewId, repo, prId, title, author, authorId, sourceBranch, destBranch, sha,
                htmlUrl, providerType, "reviewing", STAGE_DIFF, false);
    }

    /** @param claimRun whether this write may set status/stage — false preserves the row's outcome
     *                  (the status/stage arguments then apply only to a first INSERT). */
    private void upsertHeader(String reviewId, RepoRef repo, long prId, String title, String author,
                              String authorId, String sourceBranch, String destBranch, String sha,
                              String htmlUrl, String providerType, String status, int stage,
                              boolean claimRun) {
        String outcomeColumns = claimRun
                ? "status = EXCLUDED.status, stage = EXCLUDED.stage, attempt = 1, "
                        + "error_detail = NULL, answering = FALSE,"
                : "";
        String sql = """
                INSERT INTO review_status (review_id, workspace, slug, pr_id, title, author, author_id,
                        source_branch, dest_branch, commit_sha, html_url, provider_type, status, stage,
                        attempt, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, 1, now())
                ON CONFLICT (review_id) DO UPDATE SET
                        title = EXCLUDED.title, author = EXCLUDED.author, author_id = EXCLUDED.author_id,
                        source_branch = EXCLUDED.source_branch, dest_branch = EXCLUDED.dest_branch,
                        commit_sha = EXCLUDED.commit_sha, html_url = EXCLUDED.html_url,
                        provider_type = EXCLUDED.provider_type,
                        %s updated_at = now()
                """.formatted(outcomeColumns);
        update(sql, ps -> {
            ps.setString(1, reviewId);
            ps.setString(2, repo.workspace());
            ps.setString(3, repo.slug());
            ps.setLong(4, prId);
            ps.setString(5, title);
            ps.setString(6, author);
            ps.setString(7, authorId);
            ps.setString(8, sourceBranch);
            ps.setString(9, destBranch);
            ps.setString(10, sha);
            ps.setString(11, htmlUrl);
            ps.setString(12, providerType == null ? "" : providerType);
            ps.setString(13, status);
            ps.setInt(14, stage);
        });
        broadcast(reviewId);
    }

    /** Set status only, keeping the current stage (terminal failures/cancels). */
    public void updateStatus(String reviewId, String status) {
        update("UPDATE review_status SET status = ?, updated_at = now() WHERE review_id = ?", ps -> {
            ps.setString(1, status);
            ps.setString(2, reviewId);
        });
        broadcast(reviewId);
    }

    /**
     * The aggregate's terminal failure, projected without coarsening a status the saga already made
     * more specific.
     *
     * <p>A spend cap's refusal reaches its end state through the same {@code RecordFailure} every
     * other terminal failure uses: the aggregate has ONE terminal-failure event, and a second one
     * would change the wire contract for a distinction only the read model draws. So {@code refused}
     * is a refinement of {@code ReviewFailedTerminally}, and projecting that event unconditionally
     * relabelled the refusal {@code failed} one Kafka round trip after {@code ResultSaga.refuse}
     * wrote it — invisibly, since the note stayed right while the badge, the reviews list and the
     * REVIEW_FAILED attention row all read the status.
     */
    public void projectTerminalFailure(String reviewId) {
        update("""
                UPDATE review_status SET status = 'failed', updated_at = now()
                 WHERE review_id = ? AND lower(status) <> 'refused'
                """, ps -> ps.setString(1, reviewId));
        broadcast(reviewId);
    }

    /**
     * The saga's terminal failure, under the same guard — status, stage, note and the provider/worker
     * error in ONE statement.
     *
     * <p>{@code ResultSaga.onReviewFailed} is the OTHER writer of a terminal status, and it wrote
     * straight through {@code updateStatus} with no precondition. {@code ReviewFailed} is also the one
     * result the saga does not wrap in its stale-run guard, so the aggregate already being terminal did
     * not stop it either: a replayed failure (cs.results is at-least-once, and DLQ replay is deliberate)
     * relabelled a refusal, overwrote the note that explains it, and populated {@code error_detail} —
     * the field ADR-025 leaves unset for a policy decision precisely so the detail page does not show an
     * infrastructure fault.
     *
     * <p>One statement rather than three, so the guard cannot hold for the badge while the note and the
     * error are overwritten regardless, and so the broadcast carries a row that agrees with itself.
     */
    public void projectTerminalFailure(String reviewId, int stage, String note, String error) {
        String storedError = encryptedError(error, reviewId);
        update("""
                UPDATE review_status
                   SET status = 'failed', stage = ?, note = ?, error_detail = ?, updated_at = now()
                 WHERE review_id = ? AND lower(status) <> 'refused'
                """, ps -> {
            ps.setInt(1, stage);
            ps.setString(2, note);
            ps.setString(3, storedError);
            ps.setString(4, reviewId);
        });
        broadcast(reviewId);
    }

    public void updateStatus(String reviewId, String status, int stage) {
        update("UPDATE review_status SET status = ?, stage = ?, updated_at = now() WHERE review_id = ?", ps -> {
            ps.setString(1, status);
            ps.setInt(2, stage);
            ps.setString(3, reviewId);
        });
        broadcast(reviewId);
    }

    public void updateStage(String reviewId, int stage) {
        update("UPDATE review_status SET stage = ?, updated_at = now() WHERE review_id = ?", ps -> {
            ps.setInt(1, stage);
            ps.setString(2, reviewId);
        });
        broadcast(reviewId);
    }

    /** The PR's own Open/Merged/Closed state — independent of the review status (fix: PR-state badge). */
    public void setPrState(String reviewId, String prState) {
        update("UPDATE review_status SET pr_state = ?, updated_at = now() WHERE review_id = ?", ps -> {
            ps.setString(1, prState);
            ps.setString(2, reviewId);
        });
        broadcast(reviewId);
    }

    public void setNote(String reviewId, String note) {
        update("UPDATE review_status SET note = ?, updated_at = now() WHERE review_id = ?", ps -> {
            ps.setString(1, note);
            ps.setString(2, reviewId);
        });
    }

    /**
     * The operator acknowledged this review's current stuck/failed state, so the attention panel
     * stops raising a row for it. A later failure raises it again, because the panel compares this
     * against {@code updated_at}.
     *
     * <p>Deliberately does NOT bump {@code updated_at}, unlike every other write here. The panel's
     * predicate is {@code updated_at > attention_ack_at}; bumping both would leave them equal and
     * make the acknowledgement a no-op — or, worse, race into re-raising the row it just dismissed.
     */
    public void acknowledgeAttention(String reviewId) {
        update("UPDATE review_status SET attention_ack_at = now() WHERE review_id = ?",
                ps -> ps.setString(1, reviewId));
        attention.refresh();
    }

    /**
     * Bump the review's updated_at and push a fresh summary to live clients — used by
     * activity that changes detail-page data (conversation turns, follow-up costs)
     * without going through a status/stage write, which would otherwise broadcast.
     */
    public void touch(String reviewId) {
        update("UPDATE review_status SET updated_at = now() WHERE review_id = ?", ps -> {
            ps.setString(1, reviewId);
        });
        broadcast(reviewId);
    }

    /**
     * Transient "the bot is answering a reply" hint (fix #5): set true when a follow-up is
     * dispatched. Best-effort UI signal, not part of the aggregate — cleared on
     * {@code FollowUpPosted} (normal completion) and, failing that, reset on the NEXT review run
     * via {@link #registerHeader}: a follow-up that terminally DLQs (no {@code ReviewFailed}
     * reaches this flag) leaves it set only until that next run, never indefinitely. Also bumps
     * updated_at and broadcasts, so callers should not additionally call {@link #touch} for the
     * same state change (would double-broadcast).
     */
    public void setAnswering(String reviewId, boolean answering) {
        update("UPDATE review_status SET answering = ?, updated_at = now() WHERE review_id = ?", ps -> {
            ps.setBoolean(1, answering);
            ps.setString(2, reviewId);
        });
        broadcast(reviewId);
    }

    /**
     * Persist the technical error behind a terminal failure so the UI can show WHY
     * a review failed. Encrypted at rest (AAD = reviewId, like findings) and bounded
     * — a provider error can be a large blob and may echo fragments of the diff.
     */
    public void setError(String reviewId, String error) {
        String stored = encryptedError(error, reviewId);
        update("UPDATE review_status SET error_detail = ?, updated_at = now() WHERE review_id = ?", ps -> {
            ps.setString(1, stored);
            ps.setString(2, reviewId);
        });
    }

    /** Encrypt-at-rest with the review as AAD, truncated to the column; blank and null store as NULL. */
    private String encryptedError(String error, String reviewId) {
        if (error == null || error.isBlank()) {
            return null;
        }
        String stripped = error.strip();
        return encryption.encryptString(stripped.substring(0, Math.min(stripped.length(), 4000)), reviewId);
    }

    /** The current attempt (pipeline run) count for a review; 1 when unknown (C8 retry budget). */
    public int currentAttempt(String reviewId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT attempt FROM review_status WHERE review_id = ?")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt("attempt") : 1;
            }
        } catch (SQLException e) {
            LOG.warnf(e, "currentAttempt read failed for %s", reviewId);
            return 1;
        }
    }

    /** Bump the attempt counter and put the review back into REVIEWING at the diff step for a retry. */
    public void retryPipeline(String reviewId, int attempt, String note) {
        update("""
                UPDATE review_status SET attempt = ?, status = 'reviewing', stage = ?, note = ?, updated_at = now()
                WHERE review_id = ?
                """, ps -> {
            ps.setInt(1, attempt);
            ps.setInt(2, STAGE_DIFF);
            ps.setString(3, note);
            ps.setString(4, reviewId);
        });
        broadcast(reviewId);
    }

    /**
     * Schedule the next attempt instead of dispatching it now: bumps the attempt counter, keeps the row
     * in {@code reviewing} (the run IS still in flight, just waiting) and records when it comes due.
     */
    public void scheduleRetry(String reviewId, int attempt, String note, Instant dueAt) {
        // Guarded like projectTerminalFailure, and for the worse half of the same defect: a replayed
        // RETRYABLE failure would drag a refused review back to 'reviewing' with a fresh due time, and
        // the sweeper would then re-run the pipeline and spend again on a review policy already refused.
        // refuse() clears retry_at for exactly that reason; this is the second defence, on the write.
        update("""
                UPDATE review_status
                   SET attempt = ?, status = 'reviewing', stage = ?, note = ?, retry_at = ?, updated_at = now()
                 WHERE review_id = ? AND lower(status) <> 'refused'
                """, ps -> {
            ps.setInt(1, attempt);
            ps.setInt(2, STAGE_DIFF);
            ps.setString(3, note);
            ps.setTimestamp(4, Timestamp.from(dueAt));
            ps.setString(5, reviewId);
        });
        broadcast(reviewId);
    }

    /**
     * Claim every review whose retry has come due, clearing {@code retry_at} in the SAME statement that
     * reads it. Only one caller can win a given row, so replicas (or an overlapping sweep) cannot both
     * dispatch the same attempt — the claim IS the dispatch permit.
     *
     * <p>Skips archived reviews. {@link #archiveReview} already clears {@code retry_at}, and this is
     * the second of the two defences on purpose: a due time can arrive AFTER the archive lands — from
     * a result still in flight, or a replica mid-dispatch — and resurrecting a retired review minutes
     * later is exactly the outcome archival promises will not happen.
     */
    public List<String> claimDueRetries(Instant now) {
        List<String> claimed = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE review_status SET retry_at = NULL, updated_at = now()
                      WHERE retry_at IS NOT NULL AND retry_at <= ? AND archived_at IS NULL
                      RETURNING review_id
                     """)) {
            ps.setTimestamp(1, Timestamp.from(now));
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    claimed.add(rs.getString(1));
                }
            }
        } catch (SQLException e) {
            // A sweep that cannot read the DB must not kill the scheduler: the next tick tries again,
            // and the rows stay claimable because nothing was cleared.
            LOG.warnf(e, "Could not claim due review retries");
            return List.of();
        }
        claimed.forEach(this::broadcast);
        return claimed;
    }

    /** Cancel a scheduled retry — the run reached a terminal state before it came due. */
    public void clearScheduledRetry(String reviewId) {
        update("UPDATE review_status SET retry_at = NULL WHERE review_id = ? AND retry_at IS NOT NULL",
                ps -> ps.setString(1, reviewId));
    }

    /**
     * Put a claimed retry back on the clock. The claim already cleared the due time, so a dispatch that
     * fails afterwards would otherwise leave the review waiting on a retry nobody will send.
     */
    public void rescheduleRetry(String reviewId, Instant dueAt) {
        update("UPDATE review_status SET retry_at = ?, updated_at = now() WHERE review_id = ?", ps -> {
            ps.setTimestamp(1, Timestamp.from(dueAt));
            ps.setString(2, reviewId);
        });
    }

    /** Record the generated review's findings against the row. Usage/cost live on the ledger
     *  ({@code llm_charge}), written separately — see roadmap item 11. */
    public void recordOutcome(String reviewId, ReviewResult result, int stage) {
        // Findings quote the source under review — encrypt at rest (AAD = reviewId).
        String findingsJson = encryption.encryptString(toFindingsJson(result.findings()), reviewId);
        update("""
                UPDATE review_status SET findings_count = ?, findings_json = ?, stage = ?, updated_at = now()
                WHERE review_id = ?
                """, ps -> {
            ps.setInt(1, result.findings().size());
            ps.setString(2, findingsJson);
            ps.setInt(3, stage);
            ps.setString(4, reviewId);
        });
        broadcast(reviewId);
    }

    /**
     * Snapshot the run that actually reached the SCM — the source for the next
     * follow-up's {@link PriorRun} (ADR-019). Copies {@code open_findings_json}
     * verbatim (already encrypted with AAD = reviewId) rather than re-encrypting —
     * the carry-forward baseline (this round's new findings + prior still-open/
     * unchanged ones, ADR-019 refinement) — falling back to {@code findings_json}
     * when it is NULL (a review predating {@link #recordOpenFindings}, or one that
     * skipped it), which preserves the original copy-verbatim behavior.
     *
     * <p>Guarded by {@code commit_sha}: the UPDATE only applies when {@code commit}
     * still matches the review's current commit. A superseded run's CommentsPosted
     * — reachable only through the worker's head-re-check race — carries a stale
     * commit that can no longer match (a newer run's header/outcome write has since
     * advanced {@code commit_sha}), so the write no-ops and the prior, consistent
     * snapshot is left in place instead of being paired with findings that may
     * already hold the newer run's data.
     */
    public void recordPosted(String reviewId, String commit, String summaryCommentId) {
        update("""
                UPDATE review_status SET last_posted_commit = ?, last_summary_comment_id = ?,
                       posted_findings_json = COALESCE(open_findings_json, findings_json), updated_at = now()
                 WHERE review_id = ? AND commit_sha = ?
                """, ps -> {
            ps.setString(1, commit);
            ps.setString(2, summaryCommentId);
            ps.setString(3, reviewId);
            ps.setString(4, commit);
        });
    }

    /**
     * The last POSTED run's snapshot a follow-up review reconciles against
     * (ADR-019) — empty when the PR has never been posted to. Never throws: a
     * read/decrypt/parse failure degrades to empty, same posture as {@link #parseFindings}.
     */
    public Optional<PriorRun> priorRunFor(String reviewId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT last_posted_commit, last_summary_comment_id, posted_findings_json "
                             + "FROM review_status WHERE review_id = ?")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next() || rs.getString("last_posted_commit") == null) {
                    return Optional.empty();
                }
                ThreadIndex index = buildThreadIndex(loadThreadRows(c, reviewId));
                // Defense on read: a row written before the anchor-merge fix (or written straight
                // from recordOutcome's raw findings_json, never deduped) can still hold two entries
                // at the same path:line — dedupe here too so legacy data can't produce the same
                // ambiguous-verdict-matching bug the write-side fix (recordOpenFindings) prevents
                // for new baselines.
                List<ReviewDetail.FindingView> posted =
                        dedupeByAnchor(parseFindings(rs.getString("posted_findings_json"), reviewId));
                List<PriorFinding> findings = toPriorFindings(posted, index);
                return Optional.of(new PriorRun(
                        rs.getString("last_posted_commit"), rs.getString("last_summary_comment_id"), findings));
            }
        } catch (SQLException e) {
            LOG.warnf(e, "priorRunFor read failed for %s", reviewId);
            return Optional.empty();
        }
    }

    /** Skips (with a WARN) any finding whose loc doesn't parse — never lets a malformed
     *  row throw into the saga. */
    private List<PriorFinding> toPriorFindings(List<ReviewDetail.FindingView> views, ThreadIndex index) {
        List<PriorFinding> out = new ArrayList<>();
        for (ReviewDetail.FindingView f : views) {
            try {
                out.add(toPriorFinding(f, index));
            } catch (RuntimeException e) {
                LOG.warnf("Skipping malformed posted finding for prior-run projection: %s", f.loc());
            }
        }
        return out;
    }

    /**
     * loc is "path:line" (existing format) split at the LAST ':'. Prefers the CURRENT thread for the
     * loc from the {@code review_thread} index (the freshest posted comment — so a finding re-posted
     * across rounds reconciles against its LATEST thread, not a stale, already-resolved earlier one),
     * and falls back to the entry's STORED threadRef only when it has no current review_thread row
     * (pure carry-forward — a still-open finding's original thread survives even with no row for its loc).
     */
    private PriorFinding toPriorFinding(ReviewDetail.FindingView f, ThreadIndex index) {
        int splitAt = f.loc().lastIndexOf(':');
        String path = f.loc().substring(0, splitAt);
        int line = Integer.parseInt(f.loc().substring(splitAt + 1));
        String current = index.threadByLoc().get(f.loc());
        String threadRef = current != null ? current : f.threadRef();
        return new PriorFinding(path, line, severityFromSlug(f.sev()), f.msg(), threadRef, f.origin());
    }

    /** Reverse of {@link #severitySlug} — lossy for NIT/INFO (both slug to "nit");
     *  an unrecognized slug falls back to INFO rather than throwing. */
    private static Severity severityFromSlug(String slug) {
        return switch (slug) {
            case "critical" -> Severity.BLOCKER;
            case "warning" -> Severity.MAJOR;
            case "suggestion" -> Severity.MINOR;
            case "nit" -> Severity.NIT;
            default -> Severity.INFO;
        };
    }

    /**
     * Merge each verdict with its originating prior finding (matched by threadRef,
     * falling back to path+line — a prior finding whose inline post failed has no
     * threadRef), then MERGE-UPSERT the resulting entries into the existing
     * {@code reconciliation_json} rather than replacing it wholesale: a prior round's
     * entry with no match this round is retained as-is (resolved/acknowledged history
     * stays visible on the dashboard across rounds), and a re-verdicted entry replaces
     * its earlier self in place. Only the serialization/parse steps are lenient: a
     * JSON failure logs and skips (existing state loads as empty / the write is
     * skipped) rather than throwing into the saga. A SQLException from the UPDATE
     * itself still propagates, like every other write in this class.
     */
    public void recordReconciliation(String reviewId, List<FindingVerdict> verdicts,
                                     List<PriorFinding> priorFindings) {
        List<ReconciliationEntry> incoming = verdicts.stream()
                .map(v -> toReconciliationEntry(v, matchPriorFinding(v, priorFindings)))
                .toList();
        List<ReconciliationEntry> merged = mergeReconciliation(loadReconciliationEntries(reviewId), incoming);
        writeReconciliation(reviewId, merged);
    }

    private PriorFinding matchPriorFinding(FindingVerdict v, List<PriorFinding> priorFindings) {
        return priorFindings.stream()
                .filter(f -> v.threadRef() != null && v.threadRef().equals(f.threadRef()))
                .findFirst()
                .or(() -> priorFindings.stream()
                        .filter(f -> f.path().equals(v.path()) && f.line() == v.line())
                        .findFirst())
                .orElse(null);
    }

    /** {@code origin} comes from the matched prior finding, like severity and message: a verdict
     *  carries none of the three, and once a conversation finding is reconciled its verdict row is
     *  the only place the dashboard can still say a person filed it. Null when nothing matched —
     *  the same "review-derived" reading a row stored before the field existed has. */
    private ReconciliationEntry toReconciliationEntry(FindingVerdict v, PriorFinding match) {
        String sev = severitySlug(match == null ? Severity.INFO : match.severity());
        String msg = match == null ? "" : match.message();
        String origin = match == null ? null : match.origin();
        return new ReconciliationEntry(sev, v.path() + ":" + v.line(), msg, v.status().name(), v.note(),
                v.threadRef(), origin);
    }

    /** The stored {@code reconciliation_json}, decrypted and parsed — empty (never throws) on a
     *  missing column, decrypt failure, or parse failure, same posture as {@link #parseReconciliation}. */
    private List<ReconciliationEntry> loadReconciliationEntries(String reviewId) {
        String stored;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT reconciliation_json FROM review_status WHERE review_id = ?")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                stored = rs.next() ? rs.getString(1) : null;
            }
        } catch (SQLException e) {
            LOG.warnf(e, "reconciliation_json read failed for %s", reviewId);
            return List.of();
        }
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        String json;
        try {
            json = encryption.decryptString(stored, reviewId);
        } catch (RuntimeException notEncrypted) {
            json = stored; // legacy plaintext row
        }
        try {
            return mapper.readerForListOf(ReconciliationEntry.class).readValue(json);
        } catch (Exception e) {
            LOG.debugf("Failed to parse reconciliation_json for merge: %s", e.getMessage());
            return List.of();
        }
    }

    /** Verdict statuses (as stored — enum {@code name()}, not the display slug) that represent
     *  permanently closed history rather than an actively tracked open concern. */
    private static final Set<String> CLOSED_STATUSES = Set.of("RESOLVED", "ACKNOWLEDGED", "SUPERSEDED");

    /**
     * Key = threadRef when non-null, else loc. An incoming entry replaces the existing entry at the
     * same key IN PLACE (keeps its position — a re-verdicted finding doesn't jump to the bottom); an
     * incoming entry with no existing match is appended. An existing entry with NO match this round
     * is retained only when its status is closed (RESOLVED/ACKNOWLEDGED/SUPERSEDED) — that is
     * permanent history and stays visible on the dashboard across rounds. An existing entry with an
     * OPEN status (STILL_OPEN/UNCHANGED) and no match this round is DROPPED: it was merged into
     * another tracked entry (anchor dedupe) or otherwise exited tracking, and carrying it forward
     * unchanged would leave an un-updatable ghost row that can never be re-verdicted again.
     *
     * <p>Invariant: the view's open rows always correspond to currently-tracked findings; its
     * closed rows are permanent history.
     */
    private List<ReconciliationEntry> mergeReconciliation(List<ReconciliationEntry> existing,
                                                          List<ReconciliationEntry> incoming) {
        Map<String, ReconciliationEntry> byKey = new HashMap<>();
        for (ReconciliationEntry e : incoming) {
            byKey.put(reconciliationKey(e), e);
        }
        List<ReconciliationEntry> merged = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (ReconciliationEntry old : existing) {
            String key = reconciliationKey(old);
            ReconciliationEntry replacement = byKey.get(key);
            if (replacement != null) {
                merged.add(replacement);
                seen.add(key);
            } else if (CLOSED_STATUSES.contains(old.status())) {
                merged.add(old);
                seen.add(key);
            }
            // else: an unmatched OPEN-status entry is a ghost — drop it.
        }
        for (ReconciliationEntry e : incoming) {
            if (seen.add(reconciliationKey(e))) {
                merged.add(e);
            }
        }
        return merged;
    }

    private static String reconciliationKey(ReconciliationEntry e) {
        return e.threadRef() != null ? "t:" + e.threadRef() : "l:" + e.loc();
    }

    private void writeReconciliation(String reviewId, List<ReconciliationEntry> entries) {
        String json;
        try {
            json = mapper.writeValueAsString(entries);
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to serialize reconciliation verdicts", e);
            return;
        }
        String encrypted = encryption.encryptString(json, reviewId);
        update("UPDATE review_status SET reconciliation_json = ?, updated_at = now() WHERE review_id = ?", ps -> {
            ps.setString(1, encrypted);
            ps.setString(2, reviewId);
        });
    }

    /** Wire shape for {@code reconciliation_json}: one entry per verdict, merged with its finding.
     *  A stored entry written before {@code origin} existed simply has no such key and reads back
     *  null — review-derived, which is what every one of them is. */
    private record ReconciliationEntry(String sev, String loc, String msg, String status, String note,
                                       String threadRef, String origin) {
    }

    /**
     * The carry-forward baseline for the NEXT follow-up's reconciliation (ADR-019 refinement) —
     * this round's brand-new findings (from {@code result}, threadRef unset — resolved at read time
     * via the thread-index join like a fresh {@code findings_json} row) UNION every prior finding
     * still open after this round's verdicts (STILL_OPEN/UNCHANGED, or no matching verdict at all —
     * carrying an unmatched prior finding is the safer default over silently dropping it) UNION any
     * conversation finding ({@link #addConversationFinding}) this round's verdicts never judged (see
     * {@link #unmatchedConversationFindings}). A carried finding keeps its ORIGINAL
     * threadRef/severity/message so it survives even when no {@code review_thread} row exists for
     * its loc. Written to {@code open_findings_json}, encrypted (AAD = reviewId); lenient on
     * serialization failure (WARN + skip), like {@link #recordReconciliation}.
     *
     * <p><b>Origin is carried per entry, not re-derived.</b> {@link #stillOpenPriorFindings} sources
     * each carried finding's {@code origin} straight from its {@link PriorFinding#origin()}, and
     * {@link #unmatchedConversationFindings} passes its {@code FindingView} through unchanged — so a
     * conversation finding keeps its tag across a rename or a thread superseded in the
     * {@code review_thread} index, neither of which a loc/threadRef membership test survives (both
     * used to defeat an earlier re-tag-after-dedupe pass, in one direction silently mislabelling an
     * unrelated NEW finding that happened to land on a just-vacated conversation anchor, and in the
     * other silently dropping the tag when the carried entry's own loc or threadRef moved). Two
     * same-anchor entries — one from {@link #stillOpenPriorFindings}, one from
     * {@link #unmatchedConversationFindings} — still collapse to one row at {@link #dedupeByAnchor},
     * but that overlap is harmless now: both copies already agree on {@code origin}, so it no longer
     * matters which duplicate the merge keeps.
     *
     * <p>Runs inside one locked read-modify-write ({@code SELECT ... FOR UPDATE}, {@code @Transactional}):
     * {@code ManualCommandReceived} (a {@code /finding}) and {@code ReviewGenerated} (this method) are
     * handled by different sagas off different Kafka topics for the same reviewId, so an unlocked
     * read here could race a concurrent {@link #addConversationFinding} and have one writer's baseline
     * silently overwrite the other's.
     */
    @Transactional
    public void recordOpenFindings(String reviewId, ReviewResult result, List<FindingVerdict> verdicts,
                                   List<PriorFinding> priorFindings) {
        try (Connection c = dataSource.getConnection()) {
            LockedRow row = lockRowForUpdate(c, reviewId);
            List<ReviewDetail.FindingView> currentOpen =
                    row == null ? List.of() : parseFindings(row.openJson(), reviewId);

            List<ReviewDetail.FindingView> open = new ArrayList<>();
            result.findings().forEach(f -> open.add(toView(f)));
            open.addAll(stillOpenPriorFindings(verdicts, priorFindings));
            open.addAll(unmatchedConversationFindings(currentOpen, verdicts));

            String encrypted = encryptFindings(reviewId, dedupeByAnchor(open));
            if (encrypted != null) {
                writeOpenOnly(c, reviewId, encrypted);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("review_status write failed", e);
        }
    }

    /**
     * Add a finding raised in a conversation ({@code /finding}) to the carry-forward baseline.
     *
     * <p>Deliberately {@code open_findings_json} and NOT {@code findings_json}: the latter is what
     * the review of a commit produced — a truthful record of one model call, copied to
     * {@code posted_findings_json} as the run snapshot by {@link #recordPosted}. A conversation
     * finding did not come from that call. {@code open_findings_json} is already defined as the
     * carry-forward baseline, which is exactly what this is: something now open that the next round
     * must reconcile against and exclude from re-reporting.
     *
     * <p><b>{@code posted_findings_json} is amended, never overwritten.</b> {@link #priorRunFor}
     * (what a follow-up's reconciliation reads to build its exclusion list) is keyed off that
     * column, and nothing besides {@link #recordPosted} otherwise refreshes it — which only happens
     * on the review's NEXT round. A {@code /finding} already lives on a real SCM thread (its
     * {@code threadRef} names it), so it is already "posted" in every sense that column cares about;
     * leaving it stale until some future round would mean an author who filed a finding, then pushed
     * a fix before any new review ran, gets no reconciliation credit for it. But {@code recordPosted}
     * deliberately guards its write with {@code WHERE commit_sha = ?} so a superseded run can never
     * pair the PREVIOUS run's {@code last_posted_commit} with newer findings — copying the whole
     * baseline over {@code posted_findings_json} here would bypass that guard and, on supersession or
     * a terminal post failure, promote an unposted baseline into the posted snapshot while
     * {@code last_posted_commit} still names the older run. Reading the CURRENT posted snapshot,
     * adding just this one finding, deduping, and writing back keeps the two columns' meanings
     * independent, the way {@code recordOpenFindings} and {@code recordPosted} already are. Skipped
     * entirely when {@code last_posted_commit IS NULL}: {@link #priorRunFor} returns
     * {@code Optional.empty()} in that state, so nothing reads {@code posted_findings_json} yet and
     * writing one would invent a snapshot that was never actually posted.
     *
     * <p>Runs the same {@link #dedupeByAnchor} the baseline is always written through, so a
     * {@code /finding} on a line that already has an open finding merges into it rather than
     * doubling the count.
     *
     * <p>Runs inside one locked read-modify-write, for the same concurrent-writer reason documented
     * on {@link #recordOpenFindings}. Each column is merged (and, on failure, skipped) independently
     * by {@link #mergeColumnOrSkip} — a decrypt/parse failure on one column must not stop the other
     * from being updated, and must not silently replace either with just this one finding.
     */
    @Transactional
    public void addConversationFinding(String reviewId, String threadRef, String path, int line,
                                       Severity severity, String message) {
        ReviewDetail.FindingView finding = new ReviewDetail.FindingView(severitySlug(severity),
                path + ":" + line, message, threadRef, CONVERSATION_ORIGIN);
        try (Connection c = dataSource.getConnection()) {
            LockedRow row = lockRowForUpdate(c, reviewId);
            if (row == null) {
                LOG.warnf("addConversationFinding: no review_status row for %s", reviewId);
                return;
            }

            String encryptedOpen = mergeColumnOrSkip(reviewId, "open_findings_json", row.openJson(), finding);

            if (row.lastPostedCommit() == null) {
                if (encryptedOpen != null) {
                    writeOpenOnly(c, reviewId, encryptedOpen);
                }
                return;
            }

            String encryptedPosted =
                    mergeColumnOrSkip(reviewId, "posted_findings_json", row.postedJson(), finding);
            if (encryptedOpen != null && encryptedPosted != null) {
                writeOpenAndPosted(c, reviewId, encryptedOpen, encryptedPosted);
            } else if (encryptedOpen != null) {
                writeOpenOnly(c, reviewId, encryptedOpen);
            } else if (encryptedPosted != null) {
                writePostedOnly(c, reviewId, encryptedPosted);
            } // else: both columns failed to parse -- nothing safe to write; already warned twice.
        } catch (SQLException e) {
            throw new IllegalStateException("review_status write failed", e);
        }
    }

    /**
     * Merge {@code finding} into one column's CURRENT content, or null if that content is non-null
     * but fails to decrypt/parse — a blind merge in that case ({@code parseFindings}'s ordinary
     * degrade-to-empty-list posture, correct everywhere else in this file) would silently REPLACE the
     * column with just this one finding, destroying whatever was actually stored there. This
     * read-modify-write is the one new place in the file where that degrade posture would be
     * destructive rather than merely lossy-on-read, because nothing wrote this column from scratch
     * before this method existed. Absent/blank current content is not a failure — there was
     * legitimately nothing to lose. Logs only the reviewId and column name on skip, never finding
     * text.
     */
    private String mergeColumnOrSkip(String reviewId, String columnName, String currentJson,
                                     ReviewDetail.FindingView finding) {
        Optional<List<ReviewDetail.FindingView>> parsed = tryParseFindings(currentJson, reviewId);
        if (parsed.isEmpty()) {
            LOG.warnf("addConversationFinding: %s for %s failed to decrypt/parse; skipping its write "
                    + "rather than silently replacing it with just this one finding", columnName, reviewId);
            return null;
        }
        List<ReviewDetail.FindingView> merged = new ArrayList<>(parsed.get());
        merged.add(finding);
        return encryptFindings(reviewId, dedupeByAnchor(merged));
    }

    private String encryptFindings(String reviewId, List<ReviewDetail.FindingView> findings) {
        try {
            String json = mapper.writeValueAsString(findings);
            return encryption.encryptString(json, reviewId);
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to serialize open findings", e);
            return null;
        }
    }

    private void writeOpenOnly(Connection c, String reviewId, String encryptedOpen) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE review_status SET open_findings_json = ?, updated_at = now() WHERE review_id = ?")) {
            ps.setString(1, encryptedOpen);
            ps.setString(2, reviewId);
            ps.executeUpdate();
        }
    }

    private void writeOpenAndPosted(Connection c, String reviewId, String encryptedOpen, String encryptedPosted)
            throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE review_status SET open_findings_json = ?, posted_findings_json = ?, "
                        + "updated_at = now() WHERE review_id = ?")) {
            ps.setString(1, encryptedOpen);
            ps.setString(2, encryptedPosted);
            ps.setString(3, reviewId);
            ps.executeUpdate();
        }
    }

    /** The rare counterpart to {@link #writeOpenOnly}: only reached when
     *  {@link #mergeColumnOrSkip} skipped {@code open_findings_json} (a decrypt/parse failure on
     *  it) but {@code posted_findings_json} merged fine. */
    private void writePostedOnly(Connection c, String reviewId, String encryptedPosted) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE review_status SET posted_findings_json = ?, updated_at = now() WHERE review_id = ?")) {
            ps.setString(1, encryptedPosted);
            ps.setString(2, reviewId);
            ps.executeUpdate();
        }
    }

    /** {@code open_findings_json}/{@code posted_findings_json}/{@code last_posted_commit} read
     *  together under one {@code SELECT ... FOR UPDATE} — the locked counterpart to
     *  {@link #openFindingsFor} for the mutating paths ({@link #recordOpenFindings},
     *  {@link #addConversationFinding}). Caller MUST be {@code @Transactional} and pass its own
     *  connection so the lock is held for the whole read-modify-write, not just this query; null
     *  when the review has no row yet (never throws for a missing row, same posture as every other
     *  read in this file). */
    private LockedRow lockRowForUpdate(Connection c, String reviewId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT open_findings_json, posted_findings_json, last_posted_commit "
                        + "FROM review_status WHERE review_id = ? FOR UPDATE")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new LockedRow(rs.getString("open_findings_json"),
                        rs.getString("posted_findings_json"), rs.getString("last_posted_commit"));
            }
        }
    }

    private record LockedRow(String openJson, String postedJson, String lastPostedCommit) {
    }

    /** The review's current carry-forward baseline, decrypted — empty (never throws) on a missing
     *  row, a decrypt failure, or a parse failure, same posture as {@link #priorRunFor}. Unlocked: a
     *  plain read for callers that just want to look. The mutating paths take their own locked read
     *  via {@link #lockRowForUpdate} instead, so a read here is never part of a read-modify-write. */
    public List<ReviewDetail.FindingView> openFindingsFor(String reviewId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT open_findings_json FROM review_status WHERE review_id = ?")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return List.of();
                }
                return parseFindings(rs.getString("open_findings_json"), reviewId);
            }
        } catch (SQLException e) {
            LOG.warnf(e, "openFindingsFor read failed for %s", reviewId);
            return List.of();
        }
    }

    /**
     * One anchor ({@code path:line}) = one tracked concern. Two findings sharing an anchor collapse
     * onto the same SCM thread at posting time (the second's inline post folds into the first's
     * anchor claim, {@link #attachThreadRefs}); carrying both forward as separate baseline entries
     * makes the NEXT round's verdict matching ambiguous (one threadRef, two prior findings — see
     * {@link #matchVerdict}). Grouping here, before the baseline is written, guarantees every
     * baseline this method produces has unique anchors (and, after the thread join, unique
     * threadRefs) — the group keeps the FIRST entry's severity and origin, the first non-null
     * threadRef found in the group, and concatenates distinct messages with {@code "; also: "}.
     */
    private static List<ReviewDetail.FindingView> dedupeByAnchor(List<ReviewDetail.FindingView> entries) {
        Map<String, List<ReviewDetail.FindingView>> byAnchor = new java.util.LinkedHashMap<>();
        for (ReviewDetail.FindingView e : entries) {
            byAnchor.computeIfAbsent(e.loc(), k -> new ArrayList<>()).add(e);
        }
        List<ReviewDetail.FindingView> merged = new ArrayList<>();
        for (List<ReviewDetail.FindingView> group : byAnchor.values()) {
            merged.add(mergeFindingGroup(group));
        }
        return merged;
    }

    /**
     * Collapse one same-anchor group into a single {@link ReviewDetail.FindingView} — see
     * {@link #dedupeByAnchor} for the merge rules. Message merge is idempotent: merging the same
     * constituents (in any order, even if already merged) always yields the same result.
     *
     * <p>{@code origin} follows the same "first entry wins" rule as severity: a lone conversation
     * finding (no prior entry at its anchor) keeps its {@code "conversation"} origin, while a
     * conversation finding merged into an already-tracked, review-derived concern does not retag
     * that concern — it was already open and already tracked before the human commented on it.
     */
    private static ReviewDetail.FindingView mergeFindingGroup(List<ReviewDetail.FindingView> group) {
        ReviewDetail.FindingView first = group.getFirst();
        String threadRef = group.stream().map(ReviewDetail.FindingView::threadRef)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        String msg = mergeMessages(group.stream().map(ReviewDetail.FindingView::msg).toList());
        return new ReviewDetail.FindingView(first.sev(), first.loc(), msg, threadRef, first.origin());
    }

    /** Merge a list of message strings (some may contain "; also: "-joined segments) into a single
     *  message with deduplicated constituents, preserving first-seen order. Idempotent: re-merging
     *  the result with its own constituents yields the same string. */
    private static String mergeMessages(List<String> messages) {
        java.util.LinkedHashSet<String> constituents = new java.util.LinkedHashSet<>();
        for (String msg : messages) {
            if (msg != null && !msg.isBlank()) {
                // Split on "; also: " to extract all constituent parts (including re-merged ones).
                for (String part : msg.split("; also: ", -1)) {
                    String trimmed = part.trim();
                    if (!trimmed.isBlank()) {
                        constituents.add(trimmed);
                    }
                }
            }
        }
        return constituents.isEmpty() ? "" :
                String.join("; also: ", constituents);
    }

    /**
     * Every prior finding this round's verdicts leave open: STILL_OPEN/UNCHANGED, or unmatched
     * (no corresponding verdict — treated as still open, safer than dropping it silently). A
     * MATCHED entry's loc comes from the VERDICT, not the prior finding — the verdict's path/line
     * is fresher (already remapped through any incremental-diff rename the worker followed, ADR-019
     * rename fix), while severity/message/threadRef/origin still come from the prior finding, which
     * the verdict does not carry. An unmatched prior finding keeps its own loc, as before.
     */
    private List<ReviewDetail.FindingView> stillOpenPriorFindings(List<FindingVerdict> verdicts,
                                                                   List<PriorFinding> priorFindings) {
        List<ReviewDetail.FindingView> carried = new ArrayList<>();
        for (PriorFinding pf : priorFindings) {
            Optional<FindingVerdict> matched = matchVerdict(pf, verdicts);
            FindingVerdict.Status status = matched.map(FindingVerdict::status).orElse(null);
            if (status == null || status == FindingVerdict.Status.STILL_OPEN
                    || status == FindingVerdict.Status.UNCHANGED) {
                String loc = matched.map(v -> v.path() + ":" + v.line()).orElse(pf.path() + ":" + pf.line());
                carried.add(new ReviewDetail.FindingView(severitySlug(pf.severity()),
                        loc, pf.message(), pf.threadRef(), pf.origin()));
            }
        }
        return carried;
    }

    /** Which of this round's verdicts judges the given prior finding — matched by threadRef when
     *  both are non-null, else by path+line (reverse direction of {@link #matchPriorFinding}). */
    private Optional<FindingVerdict> matchVerdict(PriorFinding pf, List<FindingVerdict> verdicts) {
        return verdicts.stream()
                .filter(v -> pf.threadRef() != null && pf.threadRef().equals(v.threadRef()))
                .findFirst()
                .or(() -> verdicts.stream()
                        .filter(v -> v.path().equals(pf.path()) && v.line() == pf.line())
                        .findFirst());
    }

    /**
     * Conversation findings ({@link #addConversationFinding}) — from the CURRENT
     * {@code open_findings_json}, already read once by the caller's locked row — that this round's
     * verdicts never judged: the case a {@code /finding} filed between a round's {@code PriorRun}
     * snapshot (taken when its command was dispatched) and that round's completion produces.
     * {@link #recordOpenFindings} REPLACES {@code open_findings_json} wholesale from {@code result}
     * and {@code priorFindings} alone; a finding the command never carried is in neither, so without
     * this it would be silently destroyed rather than merely delayed a round.
     *
     * <p>A conversation finding the command DID carry (filed before the snapshot was taken) is
     * already a {@link PriorFinding} by then and is ALSO carried by {@link #stillOpenPriorFindings}
     * when unmatched — matched here the same way that method matches one, by threadRef, else by loc,
     * but that overlap is not filtered out here; it is a harmless duplicate at dedupe time
     * ({@link #dedupeByAnchor} collapses it to one row) — harmless because both copies now carry the
     * same origin ({@link PriorFinding#origin()} on one, the CURRENT open_findings_json row unchanged
     * on the other), so it no longer matters which duplicate the merge keeps.
     */
    private List<ReviewDetail.FindingView> unmatchedConversationFindings(
            List<ReviewDetail.FindingView> currentOpen, List<FindingVerdict> verdicts) {
        List<ReviewDetail.FindingView> preserved = new ArrayList<>();
        for (ReviewDetail.FindingView f : currentOpen) {
            if (CONVERSATION_ORIGIN.equals(f.origin()) && verdicts.stream().noneMatch(v ->
                    (f.threadRef() != null && f.threadRef().equals(v.threadRef()))
                            || (v.path() + ":" + v.line()).equals(f.loc()))) {
                preserved.add(f);
            }
        }
        return preserved;
    }

    /**
     * Archive a review: it leaves the live list but nothing is destroyed — not the timeline, not the
     * event stream, not the worker's claims or context blob, and above all not the charge ledger.
     * Deleting the ledger was how real paid usage disappeared with a row removed for being clutter.
     *
     * <p>Broadcasts a REMOVAL rather than an update. The row leaves the LIVE list, and an archived
     * review is frozen, so it needs no further live updates; the socket's reconnect snapshot replaces
     * the client's whole list, so an archived row pushed through it would be dropped on every
     * reconnect anyway. Show-archived is a plain REST fetch.
     */
    public ArchiveOutcome archiveReview(String workspace, String slug, long pr) {
        String reviewId = ReviewIds.reviewId(new RepoRef(workspace, slug), pr);
        ArchiveOutcome outcome = archiveRow(reviewId);
        if (outcome == ArchiveOutcome.ARCHIVED) {
            broadcastRemoval(reviewId);
        }
        return outcome;
    }

    /**
     * The archiving transaction itself.
     *
     * <p>Clears {@code retry_at} because {@link #claimDueRetries} sweeps every five seconds and would
     * otherwise resurrect the review minutes later, and {@code answering} so an archived review does
     * not display a responding pill forever.
     *
     * <p>Refuses while running: {@code ResultSaga.ifCurrentRun} guards on commit alone, so an in-flight
     * worker's results would still write status, findings and charges to a row that is supposed to be
     * frozen — and those late charges would carry a NULL archived_at into a future purge, becoming
     * exactly the orphan the column exists to prevent.
     */
    private ArchiveOutcome archiveRow(String reviewId) {
        String sql = """
                UPDATE review_status
                   SET archived_at = now(), retry_at = NULL, answering = false, updated_at = now()
                 WHERE review_id = ? AND archived_at IS NULL AND lower(status) <> 'reviewing'
                """;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, reviewId);
                ArchiveOutcome outcome = ps.executeUpdate() > 0
                        ? ArchiveOutcome.ARCHIVED : whyNotArchived(c, reviewId);
                c.commit();
                return outcome;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                // The connection goes back to a pool — leaving it in manual-commit mode would hand the
                // next borrower a transaction it never opened.
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to archive " + reviewId, e);
        }
    }

    /** Which of the three non-archiving cases applies, read inside the archiving transaction. */
    private ArchiveOutcome whyNotArchived(Connection c, String reviewId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT archived_at FROM review_status WHERE review_id = ?")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return ArchiveOutcome.NOT_FOUND;
                }
                // The row exists and the UPDATE skipped it, so exactly one of the two remaining
                // predicates rejected it: it is already archived, or it is still running.
                return rs.getTimestamp("archived_at") != null
                        ? ArchiveOutcome.ALREADY_ARCHIVED
                        : ArchiveOutcome.STILL_RUNNING;
            }
        }
    }

    /** Undo an archive. One statement, because archiving stamped nothing else. */
    public boolean unarchiveReview(String workspace, String slug, long pr) {
        String reviewId = ReviewIds.reviewId(new RepoRef(workspace, slug), pr);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE review_status SET archived_at = NULL, updated_at = now()
                      WHERE review_id = ? AND archived_at IS NOT NULL
                     """)) {
            ps.setString(1, reviewId);
            boolean restored = ps.executeUpdate() > 0;
            if (restored) {
                broadcast(reviewId);
            }
            return restored;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to unarchive " + reviewId, e);
        }
    }

    /**
     * Whether this PR has a registered review at all — a row in {@code review_status}.
     *
     * <p>Asked by paths that would otherwise write a finding, a note or a status into nothing: the
     * read-model writers degrade to a WARN on a missing row (the right posture for a projector racing
     * a registration), which leaves a caller unable to tell "written" from "dropped".
     *
     * <p>Deliberately its own query rather than {@code commitOf(...).isPresent()}. Those accessors
     * answer {@code Optional.empty()} both for a missing row AND for a NULL column, so a caller using
     * one to mean "registered" is right only for as long as every registration keeps writing that
     * column.
     *
     * <p>Also deliberately NOT fail-open like {@link #archived}: there the fallback is what the code
     * did before archival existed, and failing closed would silence a live review. Here a read fault
     * has no safe answer, so it propagates — the caller's message dead-letters with the aggregate
     * untouched, and a replay files the finding for real.
     */
    public boolean registered(String reviewId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM review_status WHERE review_id = ?")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read registration state for " + reviewId, e);
        }
    }

    /**
     * Whether this review has been archived — the gate every resurrection path consults.
     *
     * <p>Reads almost identically to {@link #registered} above and fails the OPPOSITE way, so the
     * two are worth reading together: a fault here answers "live" and lets the event through, while
     * a fault there propagates. See the catch below, and {@code registered}'s javadoc, for why each
     * posture is the safe one for its own question.
     */
    public boolean archived(String reviewId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM review_status WHERE review_id = ? AND archived_at IS NOT NULL")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        } catch (SQLException e) {
            // Fail OPEN: failing closed would silently retire a live review on a transient read fault
            // and stop every reply on it. Proceed as the code did before archival existed.
            LOG.errorf(e, "Could not read archival state for %s — treating as live", reviewId);
            return false;
        }
    }

    /**
     * Release the archived-notice claim so a later re-archive notifies again.
     *
     * <p>Targeted rather than {@link #clearWorkerIdempotency}, which would also drop the cached LLM
     * result and make the next event pay for the model a second time. Three binds, so it does not go
     * through {@link #deleteBy} — that helper exists for the single-id deletes.
     */
    public void releaseArchivedNoticeClaim(String reviewId) {
        try (Connection c = dataSource.getConnection()) {
            if (!tableExists(c, "worker.comment_idempotency")) {
                return;
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    DELETE FROM worker.comment_idempotency
                     WHERE review_id = ? AND commit = ? AND anchor_key = ?
                    """)) {
                ps.setString(1, reviewId);
                ps.setString(2, ArchivedNotice.SLOT);
                ps.setString(3, ArchivedNotice.KEY);
                ps.executeUpdate();
            }
        } catch (SQLException e) {
            // Never fatal to the unarchive it follows: the review is already restored, and the worst
            // case is a re-archive that stays quiet rather than one that resurrects anything.
            LOG.errorf(e, "Could not release the archived-notice claim for %s", reviewId);
        }
    }

    private int deleteBy(Connection c, String sql, String reviewId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reviewId);
            return ps.executeUpdate();
        }
    }

    /** Whether a (schema-qualified) relation exists — {@code to_regclass} yields NULL if not. */
    private boolean tableExists(Connection c, String qualifiedName) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT to_regclass(?)")) {
            ps.setString(1, qualifiedName);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() && rs.getString(1) != null;
            }
        }
    }

    /**
     * Delete ALL worker-owned per-review state (separate service, {@code worker} schema): its
     * idempotency claims (cached LLM result + posted-comment slots) AND its assembled-context blobs.
     * Each guarded independently because the worker schema — or a given table within it — may be absent
     * (worker never started / migrated). Used by {@link #clearWorkerIdempotency} (re-run), which must
     * leave no worker orphans behind. Archiving deliberately does NOT call it: an archived review keeps
     * everything, including the cached result and the assembled context.
     *
     * <p>The context blob is deleted by {@code review_id}, so it catches every blob a review owns,
     * including ones superseded across re-runs — content and reference vanish together (no orphaned blob).
     */
    private void deleteWorkerClaims(Connection c, String reviewId) throws SQLException {
        if (tableExists(c, "worker.comment_idempotency")) {
            deleteBy(c, "DELETE FROM worker.comment_idempotency WHERE review_id = ?", reviewId);
        }
        if (tableExists(c, "worker.context_blob")) {
            deleteBy(c, "DELETE FROM worker.context_blob WHERE review_id = ?", reviewId);
        }
    }

    /**
     * Clear ONLY the worker's cached result + comment claims, keeping the orchestrator review intact.
     * Used by a manual re-run so the worker actually re-runs the LLM (instead of re-emitting the stored
     * result) and posts fresh comments.
     */
    public void clearWorkerIdempotency(String reviewId) {
        try (Connection c = dataSource.getConnection()) {
            deleteWorkerClaims(c, reviewId);
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to clear worker idempotency for " + reviewId, e);
        }
    }

    /** The commit the review last ran against (its stored head SHA), or empty if the review is gone. */
    public Optional<String> commitOf(String reviewId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT commit_sha FROM review_status WHERE review_id = ?")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the commit for " + reviewId, e);
        }
    }

    /**
     * The review's posted summary comment id ({@code last_summary_comment_id}, V19) — the
     * conversation "thread" a top-level (non-inline) reply is routed to, since a plain PR comment
     * has no SCM thread of its own. Empty when the review is gone, or has never had a summary
     * posted (null/blank column).
     */
    public Optional<String> summaryRefOf(String reviewId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT last_summary_comment_id FROM review_status WHERE review_id = ?")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                String value = rs.getString(1);
                return (value == null || value.isBlank()) ? Optional.empty() : Optional.of(value);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the summary ref for " + reviewId, e);
        }
    }

    /**
     * The SCM type the review was registered under (its {@code provider_type}), used
     * to disambiguate provider resolution when a workspace name is registered on more
     * than one SCM. Empty if the review is gone or predates the stored type.
     */
    public Optional<String> providerTypeOf(String reviewId) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT provider_type FROM review_status WHERE review_id = ?")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.ofNullable(rs.getString(1)) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to read the provider type for " + reviewId, e);
        }
    }

    /**
     * Reset the row to an in-progress state for a manual re-run, clearing any prior terminal error.
     *
     * <p>Deliberately does NOT touch {@code attempt}, and {@code attempt} is deliberately NOT the run
     * identity a charge is keyed under — the obvious reuse, and wrong in both directions. It is written
     * only by {@link #retryPipeline} and {@link #scheduleRetry}, the ADR-016 auto-retry paths, which
     * keep the worker's idempotency claims so the worker re-emits its PERSISTED result: one paid call,
     * dispatched more than once, whose charges must therefore COLLAPSE. A re-run is the opposite — the
     * claims are cleared on purpose, so the LLM spends again and the charges must SEPARATE — and it
     * leaves {@code attempt} untouched, so folding that column into the key would be inert here and
     * would inflate every auto-retry into two or three charges for one call. The run number comes from
     * {@link dev.codespire.orchestrator.llm.ReviewRuns} instead, which moves on exactly the paths that
     * can spend again.
     */
    public void markRerunStarted(String reviewId) {
        update("""
                UPDATE review_status SET status = 'reviewing', stage = ?, error_detail = NULL,
                       note = 'Re-run requested.', updated_at = now()
                WHERE review_id = ?
                """, ps -> {
            ps.setInt(1, STAGE_DIFF);
            ps.setString(2, reviewId);
        });
        broadcast(reviewId);
    }

    /**
     * Append one line to the review's scoped event stream. seq is allocated
     * atomically (MAX+1 inside the INSERT) and guarded by UNIQUE(review_id, seq)
     * (V6): three independently-threaded consumers append to the same review, so
     * a writer that loses the allocation race gets 23505 and simply retries with
     * a fresh MAX — timeline order stays deterministic.
     */
    public void appendEvent(String reviewId, String lane, String type, String detail) {
        appendEvent(reviewId, lane, type, detail, null);
    }

    /** As above, tagging the row with the SCM {@code threadRef} it belongs to (null for
     *  non-conversation events) so the detail projection can group turns by thread. */
    public void appendEvent(String reviewId, String lane, String type, String detail, String threadRef) {
        String sql = """
                INSERT INTO review_event (review_id, seq, lane, type, detail, thread_ref)
                SELECT ?, COALESCE(MAX(seq), 0) + 1, ?, ?, ?, ? FROM review_event WHERE review_id = ?
                """;
        for (int attempt = 1; attempt <= SEQ_RETRY_LIMIT; attempt++) {
            try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, reviewId);
                ps.setString(2, lane);
                ps.setString(3, type);
                ps.setString(4, detail == null ? "" : detail);
                ps.setString(5, threadRef);
                ps.setString(6, reviewId);
                ps.executeUpdate();
                return;
            } catch (SQLException e) {
                if (!UNIQUE_VIOLATION.equals(e.getSQLState()) || attempt == SEQ_RETRY_LIMIT) {
                    throw new IllegalStateException("review_event write failed for " + reviewId, e);
                }
                // lost the seq race to a concurrent consumer — recompute MAX and retry
            }
        }
    }

    // ---- reads (REST + WS) -------------------------------------------------

    /**
     * The reviews list. Archived rows are excluded unless the caller asks for them: they are the
     * dashboard's default view of LIVE work, and an archived review is retired rather than in flight.
     *
     * @param includeArchived true only for the UI's explicit "Show archived" fetch. The live feed
     *                        ({@code ReviewsSocket}) always passes false — its reconnect snapshot
     *                        replaces the client's whole list, so archived rows pushed through it
     *                        would vanish on every reconnect.
     */
    public List<ReviewSummary> listSummaries(boolean includeArchived) {
        // The model/vendor badges and lifetime cost now come from the ledger (llm_charge) — the
        // review_status columns they used to read (model, cost_millicents) were dropped with
        // review_llm_call (roadmap 11). The ledger is empty until Task 8 starts writing charges, so
        // these read as honestly zero/uncatalogued until then, not as a stand-in for an unpriced call.
        // unpriced_calls counts distinct calls the ledger could not price, so the UI can tell "zero
        // spend" apart from "some calls have no known price yet".
        //
        // All four ledger subqueries exclude purged charges, not just the cost SUM: filtering one of
        // them would leave a dead review's model name and unpriced-call count leaking into the row of
        // the PR that later reuses its review_id. The llm_type subquery is the one to watch — the
        // filter belongs on its INNER llm_charge lookup, since the outer query reads llm_model, which
        // has no archived_at at all.
        String sql = """
                SELECT rs.*,
                       (SELECT model FROM llm_charge c
                         WHERE c.review_id = rs.review_id AND c.archived_at IS NULL
                         ORDER BY c.priced_at DESC LIMIT 1)                                      AS model,
                       (SELECT m.type FROM llm_model m
                         WHERE m.name = (SELECT model FROM llm_charge c
                                          WHERE c.review_id = rs.review_id AND c.archived_at IS NULL
                                          ORDER BY c.priced_at DESC LIMIT 1) LIMIT 1)            AS llm_type,
                       COALESCE((SELECT SUM(c.cost_millicents) FROM llm_charge c
                                  WHERE c.review_id = rs.review_id AND c.archived_at IS NULL), 0)
                                                                                                  AS total_cost_millicents,
                       COALESCE((SELECT COUNT(DISTINCT c.call_ref) FROM llm_charge c
                                  WHERE c.review_id = rs.review_id AND c.archived_at IS NULL
                                    AND c.pricing_mode = 'UNKNOWN'), 0)                          AS unpriced_calls
                  FROM review_status rs
                 WHERE (? OR rs.archived_at IS NULL)
                 ORDER BY rs.updated_at DESC
                """;
        List<ReviewSummary> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setBoolean(1, includeArchived);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(toSummary(rs));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list reviews", e);
        }
        return out;
    }

    /**
     * <b>The findings card is a UNION of two columns, not one.</b> {@code findings_json} is what one
     * model call produced; a finding a human filed with {@code /finding} never came from a model call
     * and is written to {@code open_findings_json} alone ({@link #addConversationFinding}). Reading
     * only the first column is what once made a filed finding invisible on the dashboard in every
     * round — present in the baseline, in the exclusion list and in the next round's reconciliation,
     * but nowhere a person could see it. So {@link #unionConversationFindings} adds the
     * conversation-origin entries of the second column that nothing on the page already accounts for.
     *
     * <p>A union rather than a repoint, in both directions: {@code findings_json} keeps its meaning as
     * one run's truthful outcome (the card's "+ N more" math and {@link #blockerCount} depend on it),
     * and {@code open_findings_json} keeps its meaning as the NEXT round's baseline, which also holds
     * carried prior findings that this card must not re-list as though this run raised them.
     *
     * <p>Reconciliation is read BEFORE the union, not after, because the union defers to it — see
     * {@link #alreadyTracked}. A filed finding shows as a fresh entry the round it is filed and as its
     * own verdict every round after, never as both.
     */
    public Optional<ReviewDetail> loadDetail(String workspace, String slug, long pr) {
        String reviewId = ReviewIds.reviewId(new RepoRef(workspace, slug), pr);
        try (Connection c = dataSource.getConnection()) {
            ReviewRow row = loadRow(c, reviewId);
            if (row == null) {
                return Optional.empty();
            }
            ThreadIndex threadIndex = buildThreadIndex(loadThreadRows(c, reviewId));
            List<ReviewDetail.ReconciliationView> reconciliation =
                    parseReconciliation(row.reconciliationJson, row.id, threadIndex.resolvedRefs());
            FindingsWithThreads attached = unionConversationFindings(
                    attachThreadRefs(parseFindings(row.findingsJson, row.id), threadIndex), row, reconciliation);
            Function<String, String> classifier = threadClassifier(attached.findingRefs(), threadIndex.summaryRefs());

            return Optional.of(toDetail(row,
                    loadEvents(c, reviewId, row.createdAt, classifier, threadIndex.locByThread()),
                    new ChargeDetail(chargeLines(reviewId), costOf(reviewId).unpricedCalls()),
                    attached.findings(), reconciliation));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load review " + reviewId, e);
        }
    }

    /** loc "path:line" -> threadRef (first wins on a same-line collision), which threadRefs belong
     *  to a summary comment, and which threadRefs are resolved on the SCM side — all derived once
     *  per {@link #loadDetail} / {@link #priorRunFor} call. */
    private record ThreadIndex(Map<String, String> threadByLoc, Set<String> summaryRefs,
                               Set<String> resolvedRefs, Map<String, String> locByThread) {}

    private ThreadIndex buildThreadIndex(List<ThreadRow> threadRows) {
        Map<String, String> threadByLoc = new HashMap<>();
        Set<String> summaryRefs = new HashSet<>();
        Set<String> resolvedRefs = new HashSet<>();
        // Every located thread, findings included — this direction answers "where is THIS thread",
        // which has one answer per thread, so unlike threadByLoc there is no contest to resolve.
        Map<String, String> locByThread = new HashMap<>();
        for (ThreadRow t : threadRows) {
            if (t.path() != null && t.line() != null) {
                locByThread.put(t.threadRef(), t.path() + ":" + t.line());
            }
            if (t.isSummary()) {
                summaryRefs.add(t.threadRef());
            }
            if (t.resolved()) {
                resolvedRefs.add(t.threadRef());
            }
            // is_finding, not merely "has a location" (V27): a human can start a thread ON a flagged
            // line, and since this index is last-wins by seq that newer thread would win — then
            // toPriorFindings would hand a verdict the HUMAN's thread to reply into and resolve.
            if (t.isFinding() && t.path() != null && t.line() != null) {
                // A finding re-posted at the same loc across re-review rounds (e.g. a rename made the
                // exclusion miss it) leaves several review_thread rows for one anchor. The LAST row
                // wins, and rows arrive in insertion order (seq), so a verdict targets the CURRENT
                // finding's thread rather than a stale, already-resolved older one (the "outdated
                // instead of resolved" bug). Recency comes from our own write order — never from
                // comparing thread refs, whose form is the provider's business.
                threadByLoc.put(t.path() + ":" + t.line(), t.threadRef());
            }
        }
        return new ThreadIndex(threadByLoc, summaryRefs, resolvedRefs, locByThread);
    }

    /** Findings with their owned threadRefs attached, plus the set of threadRefs a CURRENT finding
     *  claims (drives the timeline's "finding" classification). */
    private record FindingsWithThreads(List<ReviewDetail.FindingView> findings, Set<String> findingRefs) {}

    /** Attach each finding's thread. Only the FIRST finding at a given loc claims the thread — two
     *  issues on the same line must not nest the same conversation under both. */
    private FindingsWithThreads attachThreadRefs(List<ReviewDetail.FindingView> raw, ThreadIndex threadIndex) {
        List<ReviewDetail.FindingView> findings = new ArrayList<>();
        Set<String> findingRefs = new HashSet<>();
        Set<String> claimedRefs = new HashSet<>();
        for (ReviewDetail.FindingView f : raw) {
            String ref = threadIndex.threadByLoc().get(f.loc());
            if (ref != null && claimedRefs.add(ref)) {
                findingRefs.add(ref);
                findings.add(new ReviewDetail.FindingView(f.sev(), f.loc(), f.msg(), ref, f.origin()));
            } else {
                findings.add(new ReviewDetail.FindingView(f.sev(), f.loc(), f.msg(), null, f.origin()));
            }
        }
        return new FindingsWithThreads(findings, findingRefs);
    }

    /**
     * The second half of the card (see {@link #loadDetail}): the conversation-origin entries of
     * {@code open_findings_json} that nothing else on the page already accounts for.
     *
     * <p>These entries keep their STORED {@code threadRef} instead of taking one from the thread
     * index, unlike {@link #attachThreadRefs}. A {@code /finding} lives in a thread a HUMAN started,
     * which is recorded with a location but deliberately without {@code is_finding} (V27), so the
     * index has no entry for its anchor and a lookup would null the very ref that hooks the row to
     * its conversation. The index's claim rule still applies: a ref another row already owns is not
     * taken twice, so one thread can never nest under two rows.
     */
    private FindingsWithThreads unionConversationFindings(FindingsWithThreads attached, ReviewRow row,
                                                          List<ReviewDetail.ReconciliationView> reconciliation) {
        Set<String> tracked = alreadyTracked(attached.findings(), reconciliation);
        List<ReviewDetail.FindingView> untracked = untrackedConversationFindings(row, tracked);
        if (untracked.isEmpty()) {
            return attached;
        }
        List<ReviewDetail.FindingView> findings = new ArrayList<>(attached.findings());
        Set<String> findingRefs = new HashSet<>(attached.findingRefs());
        for (ReviewDetail.FindingView f : untracked) {
            String ref = f.threadRef() != null && findingRefs.add(f.threadRef()) ? f.threadRef() : null;
            findings.add(new ReviewDetail.FindingView(f.sev(), f.loc(), f.msg(), ref, f.origin()));
        }
        return new FindingsWithThreads(findings, findingRefs);
    }

    /**
     * The conversation-origin entries of the carry-forward baseline: the half of the findings card
     * {@code findings_json} structurally cannot hold, since its one writer ({@link #recordOutcome})
     * serializes a finding the model produced and nothing else.
     *
     * <p>Everything ELSE in that column is deliberately left out. It is the next round's baseline and
     * also carries prior findings that are still open; those belong to the reconciliation card, which
     * says what happened to them. Re-listing them here would make the findings card claim this run
     * raised them and would count them a second time beside their own verdict.
     */
    private List<ReviewDetail.FindingView> conversationFindings(ReviewRow row) {
        return parseFindings(row.openFindingsJson(), row.id()).stream()
                .filter(f -> CONVERSATION_ORIGIN.equals(f.origin()))
                .toList();
    }

    /**
     * The anchors and threads the page already accounts for, in {@link #keyOf}'s {@code l:}/{@code t:}
     * form: this run's own findings, plus every verdict that leaves its finding OPEN.
     *
     * <p>Deferring to an open verdict is what keeps a filed finding on exactly one row for the whole
     * life of the PR. It is a fresh entry the round it is filed and a reconciliation verdict every
     * round after, and the verdict row is the richer of the two — it says "still open" and carries the
     * model's note, where the baseline copy would render as though this run had just raised it. That
     * is also why {@link ReviewDetail.ReconciliationView} carries {@code origin}: the badge has to
     * travel to the row that wins, not to the one that yields.
     *
     * <p>A CLOSED verdict deliberately claims nothing. Its anchor is vacated, and a human filing a
     * fresh {@code /finding} on that same line is raising a new concern that must not be swallowed by
     * the resolved row still sitting in the history.
     */
    private static Set<String> alreadyTracked(List<ReviewDetail.FindingView> findings,
                                              List<ReviewDetail.ReconciliationView> reconciliation) {
        Set<String> tracked = new HashSet<>();
        findings.forEach(f -> tracked.add("l:" + f.loc()));
        for (ReviewDetail.ReconciliationView r : reconciliation) {
            if (!isOpenVerdict(r.status())) {
                continue;
            }
            tracked.add("l:" + r.loc());
            if (r.threadRef() != null) {
                tracked.add("t:" + r.threadRef());
            }
        }
        return tracked;
    }

    /**
     * The conversation findings {@code tracked} does not already account for, matched by anchor OR by
     * thread — the exact set both {@link #loadDetail}'s card and {@link #openCounts} add, so the two
     * can never disagree on what is open. MUTATES {@code tracked}, so two baseline entries sharing an
     * anchor collapse here the same way {@link #dedupeByAnchor} collapses them on the write side.
     *
     * <p>Anchor OR thread, not one of the two: an open verdict's anchor is the FRESHER of the pair
     * (already remapped through any rename the worker followed) while the baseline entry still carries
     * the thread it was filed in, so either alone leaves a gap the other closes.
     *
     * <p>It claims only the ANCHOR of what it takes, never the thread — a person can file several
     * findings from one discussion, at different lines, and they share that thread's ref. Anchor is
     * the identity here, exactly as it is for {@link #dedupeByAnchor} on the write side; claiming the
     * thread would make the second of two filed findings disappear.
     */
    private List<ReviewDetail.FindingView> untrackedConversationFindings(ReviewRow row, Set<String> tracked) {
        List<ReviewDetail.FindingView> untracked = new ArrayList<>();
        for (ReviewDetail.FindingView f : conversationFindings(row)) {
            if (tracked.contains("l:" + f.loc())
                    || (f.threadRef() != null && tracked.contains("t:" + f.threadRef()))) {
                continue;
            }
            tracked.add("l:" + f.loc());
            untracked.add(f);
        }
        return untracked;
    }

    /** A verdict that leaves its finding open rather than closing it out — the shared test behind the
     *  open counts, {@link #openFindingLocs} and {@link #alreadyTracked}. */
    private static boolean isOpenVerdict(String status) {
        return "still open".equals(status) || "unchanged".equals(status);
    }

    /** Classify a timeline turn's thread as the finding it nests under, the summary comment, or a
     *  bare mention/reply — null passes through untouched (non-conversation events). */
    private Function<String, String> threadClassifier(Set<String> findingRefs, Set<String> summaryRefs) {
        return threadRef -> {
            if (threadRef == null) {
                return null;
            }
            if (findingRefs.contains(threadRef)) {
                return "finding";
            }
            return summaryRefs.contains(threadRef) ? "summary" : "mention";
        };
    }

    /** Every charge line recorded for a review, oldest first — the cost card's raw material. */
    public List<ReviewDetail.ChargeLineView> chargeLines(String reviewId) {
        List<ReviewDetail.ChargeLineView> out = new ArrayList<>();
        // rate_millicents_per_million is deliberately NOT selected: this view is served to viewers and a
        // rate is admin-only configuration (see ChargeLineView). Leaving it out of the QUERY, not just
        // out of the record, keeps the omission from being undone by an autocompleted constructor arg.
        String sql = """
                SELECT call_ref, kind, model, token_type, tokens,
                       cost_millicents, pricing_mode, priced_at
                  FROM llm_charge WHERE review_id = ? AND archived_at IS NULL
                 ORDER BY priced_at, token_type
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    // cost is NULLABLE — read via getObject(..., Long.class) so NULL stays NULL.
                    // getLong would coerce "unpriced" back into 0, which is the bug this branch removes.
                    Long cost = rs.getObject("cost_millicents", Long.class);
                    out.add(new ReviewDetail.ChargeLineView(rs.getString("call_ref"), rs.getString("kind"),
                            rs.getString("model"), rs.getString("token_type"), rs.getInt("tokens"), cost,
                            rs.getString("pricing_mode"), rs.getTimestamp("priced_at").toInstant().toString()));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load charge lines for " + reviewId, e);
        }
        return out;
    }

    /**
     * Append one LLM call's charge lines, in ONE transaction. Idempotent: {@code ON CONFLICT DO NOTHING}
     * against the ledger's {@code UNIQUE (call_ref, token_type)}, so a redelivered result event cannot
     * charge the same call twice.
     *
     * <p>Atomic per CALL rather than per line, which is the grain that matters even though the ledger's
     * grain is the line. Written line by line in autocommit, a failure partway through committed a
     * PARTIAL call — and nothing can flag that, because the missing lines simply do not exist: the
     * unpriced-call count stays silent and the review renders a confidently understated total, which is
     * the one thing this ledger exists to prevent. It also gave each line of one call its own
     * {@code priced_at}, since {@code now()} is the TRANSACTION timestamp; sharing one transaction makes
     * a call's lines share it, so the UI's grouping by {@code call_ref} is a statement about identity
     * rather than a workaround for drift.
     */
    public void recordCharges(ChargeCall call) {
        if (call.lines().isEmpty()) {
            return; // nothing to write, so nothing to broadcast either
        }
        String sql = """
                INSERT INTO llm_charge (id, review_id, call_ref, kind, model, pricing_mode,
                        token_type, tokens, rate_millicents_per_million, cost_millicents)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (call_ref, token_type) DO NOTHING
                """;
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                for (ChargeLine line : call.lines()) {
                    bindChargeLine(ps, call, line);
                    ps.addBatch();
                }
                ps.executeBatch();
                c.commit();
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to record charges for call " + call.callRef(), e);
        }
        broadcast(call.reviewId());
    }

    private static void bindChargeLine(PreparedStatement ps, ChargeCall call, ChargeLine line)
            throws SQLException {
        ps.setObject(1, java.util.UUID.randomUUID());
        ps.setString(2, call.reviewId());
        ps.setString(3, call.callRef());
        ps.setString(4, call.kind().name());
        ps.setString(5, call.model());
        ps.setString(6, line.mode().name());
        ps.setString(7, line.tokenType().name());
        ps.setInt(8, line.tokens());
        setNullableLong(ps, 9, line.rateMillicentsPerMillion());
        setNullableLong(ps, 10, line.costMillicents());
    }

    /**
     * Bind a nullable money column. NULL must stay NULL: a rate or cost written as 0 because the value
     * was absent is the exact conflation this ledger exists to remove, and {@code setLong} on an
     * unboxed null would either throw or silently write zero depending on the call site.
     */
    private static void setNullableLong(PreparedStatement ps, int index, Long value) throws SQLException {
        if (value == null) {
            ps.setNull(index, java.sql.Types.BIGINT);
        } else {
            ps.setLong(index, value);
        }
    }

    /**
     * A review's cost.
     *
     * @param knownCostMillicents the sum of lines that COULD be priced. Deliberately not the whole
     *                            picture on its own — see {@code unpricedCalls}.
     * @param unpricedCalls       how many distinct calls have at least one unpriced line, so the UI can
     *                            say the total is partial instead of presenting it as complete
     * @param lastModel           the model on the most recent charge line, for the badge that used to
     *                            read review_status.model
     */
    public record CostSummary(long knownCostMillicents, int unpricedCalls, String lastModel) {
    }

    /**
     * A review's cost, derived from the ledger — see {@link CostSummary}.
     *
     * <p>Like every other {@code llm_charge} read, it excludes purged charges. That is not a
     * restriction on which review may see its own money: {@code archived_at} is stamped on a charge
     * only by a purge, in the transaction that hard-deletes the review row, so an archived review's
     * lines are never stamped and this still reports its full spend. What the filter excludes is the
     * ledger of a review that no longer exists, which would otherwise be read as the spend of the PR
     * that re-registers under the same {@code review_id}.
     */
    public CostSummary costOf(String reviewId) {
        String sql = """
                SELECT COALESCE(SUM(cost_millicents), 0)                                    AS known_cost,
                       COUNT(DISTINCT CASE WHEN pricing_mode = 'UNKNOWN' THEN call_ref END) AS unpriced_calls,
                       (SELECT model FROM llm_charge WHERE review_id = ? AND archived_at IS NULL
                         ORDER BY priced_at DESC LIMIT 1)                                    AS last_model
                  FROM llm_charge WHERE review_id = ? AND archived_at IS NULL
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, reviewId);
            ps.setString(2, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return new CostSummary(0L, 0, null);
                }
                return new CostSummary(rs.getLong("known_cost"), rs.getInt("unpriced_calls"),
                        rs.getString("last_model"));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to compute cost for " + reviewId, e);
        }
    }

    // ---- broadcast ---------------------------------------------------------

    private void broadcast(String reviewId) {
        // Every review write funnels through here, so it is the one place the attention panel needs
        // to learn that a review's status changed — a failure appearing, or a stall resolving. The
        // broadcaster pushes only if the condition set actually differs, so this is cheap on the
        // writes that change nothing a panel row depends on.
        attention.refresh();

        ReviewSummary summary;
        try (Connection c = dataSource.getConnection()) {
            ReviewRow row = loadRow(c, reviewId);
            if (row == null) {
                return; // header not written yet (events can race ahead) — nothing to push
            }
            // Same ledger-derived figures as listSummaries' join, computed separately here since this
            // path loads one row outside that query (plain SELECT * FROM review_status).
            String model = latestModelFor(c, reviewId);
            LedgerSummary ledger = new LedgerSummary(model, llmTypeFor(c, model),
                    cumulativeCost(c, reviewId), unpricedCallsFor(c, reviewId));
            summary = row.toSummary(ledger, openCounts(row));
        } catch (SQLException e) {
            LOG.debugf("broadcast load failed for %s: %s", reviewId, e.getMessage());
            return;
        }
        String json;
        try {
            json = mapper.writeValueAsString(summary);
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to serialize review summary", e);
            return;
        }
        push(json);
    }

    /** Tell live clients to drop a review that was just deleted. */
    private void broadcastRemoval(String reviewId) {
        String json;
        try {
            json = mapper.writeValueAsString(java.util.Map.of("removed", reviewId));
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to serialize review removal", e);
            return;
        }
        push(json);
    }

    private void push(String json) {
        connections.stream()
                .filter(conn -> "/api/ws/reviews".equals(conn.handshakeRequest().path()))
                .forEach(conn -> conn.sendText(json).subscribe().with(v -> {
                }, t -> LOG.debugf("WS push failed: %s", t.getMessage())));
    }

    // ---- row mapping -------------------------------------------------------

    /**
     * The model/vendor/cost figures a review's ledger rows resolve to — always travel together
     * ({@link #listSummaries}' join and {@link #broadcast}'s per-row lookup both produce one of
     * these), so they are a parameter object rather than four separate arguments.
     *
     * @param unpricedCalls distinct calls the ledger could not price — lets the UI tell "zero
     *                      spend" apart from "some calls have no known price yet"
     */
    private record LedgerSummary(String model, String llmType, long totalCostMillicents, int unpricedCalls) {
    }

    private record ReviewRow(String id, String workspace, String slug, long pr, String title, String author,
                             String authorId, String branch, String base, String sha, String htmlUrl,
                             String providerType, String status, boolean answering, int stage, int findings,
                             String findingsJson, String openFindingsJson, String reconciliationJson,
                             String note, String errorDetail, int attempt, Instant createdAt,
                             Instant updatedAt, String prState, Instant archivedAt) {
        ReviewSummary toSummary(LedgerSummary ledger, OpenCounts openCounts) {
            return new ReviewSummary(id, workspace, slug, slug, pr, title, author, authorId, branch, base, sha,
                    htmlUrl, providerType, status, stage, openCounts.open(), openCounts.openBlockers(),
                    openCounts.carriedOver(), ledger.totalCostMillicents(),
                    ledger.model() == null ? "" : ledger.model(),
                    ledger.llmType() == null ? "" : ledger.llmType(), updatedAt, answering, prState,
                    ledger.unpricedCalls(), archivedAt);
        }
    }

    private ReviewRow loadRow(Connection c, String reviewId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT * FROM review_status WHERE review_id = ?")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readRow(rs) : null;
            }
        }
    }

    private ReviewSummary toSummary(ResultSet rs) throws SQLException {
        ReviewRow row = readRow(rs);
        LedgerSummary ledger = new LedgerSummary(rs.getString("model"), rs.getString("llm_type"),
                rs.getLong("total_cost_millicents"), rs.getInt("unpriced_calls"));
        return row.toSummary(ledger, openCounts(row));
    }

    /** Count of blocker-severity (critical) findings on a row — drives the detail page's
     *  current-run "changes requested" outcome ({@link #toDetail}). */
    private int blockerCount(ReviewRow row) {
        return (int) parseFindings(row.findingsJson(), row.id()).stream()
                .filter(f -> "critical".equals(f.sev()))
                .count();
    }

    /**
     * @param carriedOver how many of {@code open} came from a previous run rather than this one.
     *                    Split out because a bare total moving 1 -> 2 between rounds reads as a
     *                    regression the author caused, when it can equally be one new finding beside
     *                    one that was already open and simply never fixed.
     */
    private record OpenCounts(int open, int openBlockers, int carriedOver) {
    }

    /**
     * The review's currently-open findings — this run's new findings plus reconciliation
     * entries still open (still-open/unchanged), deduped by thread. This is what the list row
     * must show (fixes #3): a review with a carried-forward open critical is NOT "passed".
     *
     * <p>Inserts with {@code putIfAbsent} (first-wins), not {@code put}: new findings are added
     * before reconciliation entries, so a same-key collision keeps the NEW finding's severity —
     * matching {@code dedupeByAnchor}/{@code mergeFindingGroup}'s first-wins rule for duplicate
     * anchors, so the list and detail can never disagree on a shared anchor's severity.
     *
     * <p>Counts exactly the rows {@link #loadDetail}'s card renders, through the same
     * {@link #alreadyTracked} / {@link #untrackedConversationFindings} pair: a finding a human filed
     * is open, so a card that lists it beside a badge reading "passed" contradicts itself on one page.
     *
     * <p>Those are added LAST, so they land in {@code carriedOver} rather than {@code newlyRaised}.
     * {@code newlyRaised} means "this run's review call raised it", and a human-filed finding never
     * came from that call — attributing it to the run would credit the model with a person's work,
     * the same mislabelling {@code origin} exists to prevent.
     */
    private OpenCounts openCounts(ReviewRow row) {
        Map<String, String> openSevByKey = new java.util.LinkedHashMap<>();
        List<ReviewDetail.FindingView> findings = parseFindings(row.findingsJson(), row.id());
        for (ReviewDetail.FindingView f : findings) {
            openSevByKey.putIfAbsent(keyOf(f.threadRef(), f.loc()), f.sev());
        }
        // Everything keyed so far is this run's own work; anything the loops below add beyond it was
        // already open before this run started. `putIfAbsent` is what makes the subtraction exact —
        // a carried finding re-reported at the same anchor stays counted once, as new, so the two
        // halves always sum to the total.
        int newlyRaised = openSevByKey.size();
        List<ReviewDetail.ReconciliationView> reconciliation =
                parseReconciliation(row.reconciliationJson(), row.id(), Set.of());
        for (ReviewDetail.ReconciliationView r : reconciliation) {
            if (isOpenVerdict(r.status())) {
                openSevByKey.putIfAbsent(keyOf(r.threadRef(), r.loc()), r.sev());
            }
        }
        for (ReviewDetail.FindingView f
                : untrackedConversationFindings(row, alreadyTracked(findings, reconciliation))) {
            // Keyed by ANCHOR, deliberately not by thread: several findings filed from one discussion
            // share that thread's ref, and keyOf prefers the thread when it has one — which would
            // collapse two separate concerns into one and undercount the card it must agree with.
            openSevByKey.putIfAbsent(keyOf(null, f.loc()), f.sev());
        }
        int blockers = (int) openSevByKey.values().stream().filter("critical"::equals).count();
        return new OpenCounts(openSevByKey.size(), blockers, openSevByKey.size() - newlyRaised);
    }

    private static String keyOf(String threadRef, String loc) {
        return threadRef != null && !threadRef.isBlank() ? "t:" + threadRef : "l:" + loc;
    }

    /**
     * Does the review still have an open finding at {@code loc} ({@code path:line})?
     *
     * <p>Lets the conversation policy treat a thread a human started ON a line the bot flagged as one
     * of the bot's own: commenting where the reviewer raised something is almost always a response to
     * it, and before this it got silence because the thread ref was unknown.
     *
     * <p>Fails CLOSED — a read error returns false, so the bot stays quiet exactly as it did before
     * this existed. Never let a database problem make it speak where it otherwise would not.
     */
    public boolean hasOpenFindingAt(String reviewId, String loc) {
        if (loc == null || loc.isBlank()) {
            return false;
        }
        try (Connection c = dataSource.getConnection()) {
            ReviewRow row = loadRow(c, reviewId);
            return row != null && openFindingLocs(row).contains(loc);
        } catch (SQLException e) {
            LOG.warnf(e, "hasOpenFindingAt read failed for %s", reviewId);
            return false;
        }
    }

    /** {@code path:line} of every finding still open — this run's, plus carried-forward ones. */
    private Set<String> openFindingLocs(ReviewRow row) {
        Set<String> locs = new HashSet<>();
        for (ReviewDetail.FindingView f : parseFindings(row.findingsJson(), row.id())) {
            if (f.loc() != null && !f.loc().isBlank()) {
                locs.add(f.loc());
            }
        }
        for (ReviewDetail.ReconciliationView r
                : parseReconciliation(row.reconciliationJson(), row.id(), Set.of())) {
            if (isOpenVerdict(r.status()) && r.loc() != null && !r.loc().isBlank()) {
                locs.add(r.loc());
            }
        }
        return locs;
    }

    /**
     * Cumulative cost for a single row loaded outside {@link #listSummaries}'s subquery (the
     * {@link #broadcast} path) — sums every {@code llm_charge} row for the review. Honestly zero
     * when there are no charges yet, never a stand-in for an unpriced one.
     */
    private long cumulativeCost(Connection c, String reviewId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COALESCE(SUM(cost_millicents), 0) FROM llm_charge"
                        + " WHERE review_id = ? AND archived_at IS NULL")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getLong(1) : 0L;
            }
        }
    }

    /** The most recently priced call's model for a review, from the ledger — {@code review_status}
     *  no longer carries a model column, so this is now the only source for it. */
    private String latestModelFor(Connection c, String reviewId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT model FROM llm_charge WHERE review_id = ? AND archived_at IS NULL"
                        + " ORDER BY priced_at DESC LIMIT 1")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    /** How many distinct calls landed on the ledger with no known price — same predicate as
     *  {@link #listSummaries}'s join, computed separately for the {@link #broadcast} path's single row. */
    private int unpricedCallsFor(Connection c, String reviewId) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT COUNT(DISTINCT call_ref) FROM llm_charge WHERE review_id = ?"
                        + " AND archived_at IS NULL AND pricing_mode = 'UNKNOWN'")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getInt(1) : 0;
            }
        }
    }

    /** The LLM vendor for a model name, from the catalog; null when uncatalogued or no model yet. */
    private String llmTypeFor(Connection c, String model) throws SQLException {
        if (model == null || model.isBlank()) {
            return null;
        }
        try (PreparedStatement ps = c.prepareStatement("SELECT type FROM llm_model WHERE name = ? LIMIT 1")) {
            ps.setString(1, model);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        }
    }

    private ReviewRow readRow(ResultSet rs) throws SQLException {
        return new ReviewRow(
                rs.getString("review_id"), rs.getString("workspace"), rs.getString("slug"), rs.getLong("pr_id"),
                rs.getString("title"), rs.getString("author"), rs.getString("author_id"),
                rs.getString("source_branch"), rs.getString("dest_branch"), rs.getString("commit_sha"),
                rs.getString("html_url"), rs.getString("provider_type"),
                rs.getString("status"), rs.getBoolean("answering"), rs.getInt("stage"), rs.getInt("findings_count"),
                rs.getString("findings_json"), rs.getString("open_findings_json"),
                rs.getString("reconciliation_json"), rs.getString("note"),
                rs.getString("error_detail"), rs.getInt("attempt"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(),
                rs.getString("pr_state"), instantOrNull(rs.getTimestamp("archived_at")));
    }

    /** NULL means live, so it must stay null — {@code getTimestamp} yields null and only the
     *  conversion needs guarding. */
    private static Instant instantOrNull(Timestamp ts) {
        return ts == null ? null : ts.toInstant();
    }

    /**
     * A review's ledger as the detail page needs it: the per-line breakdown, and how many distinct
     * calls the ledger could not price.
     *
     * <p>A parameter object for the same reason {@link LedgerSummary} is one — the two figures are
     * only meaningful together. The page sums the lines itself and an unpriced line contributes
     * nothing to that sum, so a breakdown handed over without the count produces a total that reads
     * as complete. Passing them as separate arguments invites exactly the omission that shipped: the
     * lines were plumbed through and the count was not.
     */
    private record ChargeDetail(List<ReviewDetail.ChargeLineView> lines, int unpricedCalls) {
    }

    private ReviewDetail toDetail(ReviewRow r, List<ReviewDetail.EventView> events,
                                  ChargeDetail charges,
                                  List<ReviewDetail.FindingView> findings,
                                  List<ReviewDetail.ReconciliationView> reconciliation) {
        // Same reconciled-open figures the list row shows (openCounts) — the header badge must
        // agree with the list instead of quoting this run's raw (possibly stale) outcome.
        OpenCounts openCounts = openCounts(r);
        return new ReviewDetail(r.id, r.workspace, r.slug, r.slug, r.pr, r.title, r.author, r.authorId,
                r.branch, r.base, r.sha, r.htmlUrl, r.providerType, r.status, r.answering, r.stage, r.findings,
                blockerCount(r), openCounts.open(), openCounts.openBlockers(), r.updatedAt, r.attempt,
                computeStages(r.status, r.stage),
                List.of("", "", "", "", "", ""), findings, reconciliation,
                charges.lines(), charges.unpricedCalls(), r.note, decryptError(r.errorDetail, r.id),
                events, r.prState, r.archivedAt);
    }

    /** Decrypt the stored error detail (AAD = reviewId); tolerate a legacy plaintext value. */
    private String decryptError(String stored, String reviewId) {
        if (stored == null || stored.isBlank()) {
            return null;
        }
        try {
            return encryption.decryptString(stored, reviewId);
        } catch (RuntimeException notEncrypted) {
            return stored;
        }
    }

    private record ThreadRow(String threadRef, String path, Integer line, boolean isSummary,
                             boolean resolved, boolean isFinding) {}

    private List<ThreadRow> loadThreadRows(Connection c, String reviewId) throws SQLException {
        List<ThreadRow> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                // Insertion order, so the caller's "last row per loc wins" means newest. Ordering by
                // thread_ref instead would sort by a provider-shaped string.
                "SELECT thread_ref, path, line, is_summary, resolved, is_finding FROM review_thread "
                        + "WHERE review_id = ? ORDER BY seq")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ThreadRow(rs.getString("thread_ref"), rs.getString("path"),
                            (Integer) rs.getObject("line"), rs.getBoolean("is_summary"),
                            rs.getBoolean("resolved"), rs.getBoolean("is_finding")));
                }
            }
        }
        return out;
    }

    private List<ReviewDetail.EventView> loadEvents(Connection c, String reviewId, Instant t0,
            Function<String, String> threadKind, Map<String, String> locByThread) throws SQLException {
        List<ReviewDetail.EventView> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT lane, type, detail, at, thread_ref FROM review_event WHERE review_id = ? ORDER BY seq")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Instant at = rs.getTimestamp("at").toInstant();
                    String threadRef = rs.getString("thread_ref");
                    out.add(new ReviewDetail.EventView(at.toString(), relative(t0, at), rs.getString("lane"),
                            rs.getString("type"), rs.getString("detail"), threadRef, threadKind.apply(threadRef),
                            threadRef == null ? null : locByThread.get(threadRef)));
                }
            }
        }
        return out;
    }

    // ---- derivations -------------------------------------------------------

    static List<String> computeStages(String status, int stage) {
        String[] s = new String[STEPS.size()];
        for (int i = 0; i < s.length; i++) {
            s[i] = "pending";
        }
        switch (status) {
            case "completed" -> java.util.Arrays.fill(s, "done");
            case "observed" -> s[0] = "done";
            case "failed" -> {
                int f = Math.min(Math.max(stage, 0), s.length - 1);
                for (int i = 0; i < f; i++) {
                    s[i] = "done";
                }
                s[f] = "failed";
            }
            // "refused" belongs with these and NOT with "failed": the steps that ran really did run
            // (a pre-spend refusal has already fetched its diff and assembled its context), and no step
            // failed, because nothing failed. Falling through to default drew all six grey, telling the
            // operator neither had happened -- the same "a refusal is not a failure" split ADR-025 makes
            // for the status itself.
            case "cancelled", "superseded", "refused" -> {
                int done = Math.min(Math.max(stage, 0), s.length);
                for (int i = 0; i < done; i++) {
                    s[i] = "done";
                }
            }
            case "reviewing" -> {
                int active = Math.min(Math.max(stage, 0), s.length);
                for (int i = 0; i < active; i++) {
                    s[i] = "done";
                }
                if (active < s.length) {
                    s[active] = "active";
                }
            }
            default -> { /* all pending */ }
        }
        return List.of(s);
    }

    /**
     * A friendly elapsed-since-start delta. Sub-10s keeps one decimal ("+8.0s"); beyond that it
     * steps up units so a day-later follow-up reads "+23h 57m" instead of an opaque "+86260s".
     * Zero remainders are dropped ("+5m", not "+5m 0s").
     */
    private static String relative(Instant t0, Instant at) {
        long ms = at.toEpochMilli() - t0.toEpochMilli();
        if (ms < 0) {
            ms = 0;
        }
        double secs = ms / 1000.0;
        if (secs < 10) {
            return String.format(java.util.Locale.ROOT, "+%.1fs", secs);
        }
        long total = Math.round(secs);
        if (total < 60) {
            return "+" + total + "s";
        }
        if (total < 3600) {
            return withRemainder(total / 60, total % 60, "m", "s");
        }
        if (total < 86_400) {
            return withRemainder(total / 3600, (total % 3600) / 60, "h", "m");
        }
        return withRemainder(total / 86_400, (total % 86_400) / 3600, "d", "h");
    }

    private static String withRemainder(long major, long minor, String majorUnit, String minorUnit) {
        return minor == 0 ? "+" + major + majorUnit : "+" + major + majorUnit + " " + minor + minorUnit;
    }

    private String toFindingsJson(List<Finding> findings) {
        List<ReviewDetail.FindingView> views = findings.stream().map(this::toView).toList();
        try {
            return mapper.writeValueAsString(views);
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to serialize findings", e);
            return "[]";
        }
    }

    private List<ReviewDetail.FindingView> parseFindings(String stored, String reviewId) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        String json;
        try {
            json = encryption.decryptString(stored, reviewId);
        } catch (RuntimeException notEncrypted) {
            json = stored; // legacy plaintext row
        }
        try {
            return mapper.readerForListOf(ReviewDetail.FindingView.class).readValue(json);
        } catch (Exception e) {
            LOG.debugf("Failed to parse findings_json: %s", e.getMessage());
            return List.of();
        }
    }

    /**
     * Same decrypt+parse as {@link #parseFindings}, but distinguishes "genuinely empty" from
     * "failed to parse" — empty {@link Optional} means failure, {@code Optional.of(List.of())} means
     * the column was legitimately absent/blank or decrypted to a real empty array. Needed only where
     * a failure must SKIP a write rather than silently degrade to an empty list: see
     * {@link #mergeColumnOrSkip}, where {@link #parseFindings}'s ordinary degrade-to-empty would turn
     * a decrypt/parse failure into a write that REPLACES the column with just one new finding,
     * destroying whatever was actually stored there. Never throws.
     */
    private Optional<List<ReviewDetail.FindingView>> tryParseFindings(String stored, String reviewId) {
        if (stored == null || stored.isBlank()) {
            return Optional.of(List.of());
        }
        String json;
        try {
            json = encryption.decryptString(stored, reviewId);
        } catch (RuntimeException notEncrypted) {
            json = stored; // legacy plaintext row
        }
        try {
            return Optional.of(mapper.readerForListOf(ReviewDetail.FindingView.class).readValue(json));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    /**
     * loadDetail's lenient reader for {@code reconciliation_json} — same posture as {@link
     * #parseFindings}: a null column, decrypt failure, or parse failure all degrade to an empty
     * list rather than throwing. Renders {@code status} lower-case with spaces ("still open") and
     * sets {@code resolvedThread} from the thread index (false when the entry has no threadRef,
     * since {@code Set.contains(null)} is a safe false rather than a match).
     */
    private List<ReviewDetail.ReconciliationView> parseReconciliation(String stored, String reviewId,
                                                                       Set<String> resolvedRefs) {
        if (stored == null || stored.isBlank()) {
            return List.of();
        }
        String json;
        try {
            json = encryption.decryptString(stored, reviewId);
        } catch (RuntimeException notEncrypted) {
            json = stored; // legacy plaintext row
        }
        try {
            List<ReconciliationEntry> entries = mapper.readerForListOf(ReconciliationEntry.class).readValue(json);
            return entries.stream()
                    .map(e -> new ReviewDetail.ReconciliationView(e.sev(), e.loc(), e.msg(),
                            statusDisplay(e.status()), e.note(), e.threadRef(),
                            resolvedRefs.contains(e.threadRef()), e.origin()))
                    .toList();
        } catch (Exception e) {
            LOG.debugf("Failed to parse reconciliation_json: %s", e.getMessage());
            return List.of();
        }
    }

    /** Reverse of storing {@code status.name()} in {@link #toReconciliationEntry}: "STILL_OPEN" -> "still open". */
    private static String statusDisplay(String status) {
        return status.toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    private ReviewDetail.FindingView toView(Finding f) {
        String loc = f.range() == null ? f.path() : f.path() + ":" + f.range().startLine();
        return new ReviewDetail.FindingView(severitySlug(f.severity()), loc, f.message(), null);
    }

    private static String severitySlug(Severity severity) {
        return switch (severity) {
            case BLOCKER -> "critical";
            case MAJOR -> "warning";
            case MINOR -> "suggestion";
            case INFO, NIT -> "nit";
        };
    }

    // ---- jdbc helper -------------------------------------------------------

    @FunctionalInterface
    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }

    private void update(String sql, Binder binder) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("review_status write failed", e);
        }
    }
}
