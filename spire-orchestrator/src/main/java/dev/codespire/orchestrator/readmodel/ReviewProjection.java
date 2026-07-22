package dev.codespire.orchestrator.readmodel;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.encryption.EncryptionService;
import io.quarkus.websockets.next.OpenConnections;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
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

    // ---- writes (called by the sagas) --------------------------------------

    /** Upsert the header of a review and reset its status/stage for a (re)run. */
    public void registerHeader(String reviewId, RepoRef repo, long prId, String title, String author,
                               String authorId, String sourceBranch, String destBranch, String sha,
                               String htmlUrl, String providerType, String status, int stage) {
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
                        status = EXCLUDED.status, stage = EXCLUDED.stage, attempt = 1,
                        error_detail = NULL, updated_at = now()
                """;
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

    public void setNote(String reviewId, String note) {
        update("UPDATE review_status SET note = ?, updated_at = now() WHERE review_id = ?", ps -> {
            ps.setString(1, note);
            ps.setString(2, reviewId);
        });
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
     * Persist the technical error behind a terminal failure so the UI can show WHY
     * a review failed. Encrypted at rest (AAD = reviewId, like findings) and bounded
     * — a provider error can be a large blob and may echo fragments of the diff.
     */
    public void setError(String reviewId, String error) {
        String stored = (error == null || error.isBlank())
                ? null
                : encryption.encryptString(error.strip().substring(0, Math.min(error.strip().length(), 4000)), reviewId);
        update("UPDATE review_status SET error_detail = ?, updated_at = now() WHERE review_id = ?", ps -> {
            ps.setString(1, stored);
            ps.setString(2, reviewId);
        });
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

    /** Record the generated review's findings + usage against the row. */
    public void recordOutcome(String reviewId, ReviewResult result, int stage) {
        // Findings quote the source under review — encrypt at rest (AAD = reviewId).
        String findingsJson = encryption.encryptString(toFindingsJson(result.findings()), reviewId);
        var usage = result.usage();
        update("""
                UPDATE review_status SET findings_count = ?, findings_json = ?, model = ?, tokens_in = ?,
                        tokens_out = ?, cost_millicents = ?, stage = ?, updated_at = now()
                WHERE review_id = ?
                """, ps -> {
            ps.setInt(1, result.findings().size());
            ps.setString(2, findingsJson);
            if (usage == null) {
                ps.setNull(3, java.sql.Types.VARCHAR);
                ps.setNull(4, java.sql.Types.INTEGER);
                ps.setNull(5, java.sql.Types.INTEGER);
                ps.setNull(6, java.sql.Types.BIGINT);
            } else {
                ps.setString(3, usage.model());
                ps.setInt(4, usage.tokensIn());
                ps.setInt(5, usage.tokensOut());
                ps.setLong(6, usage.costMillicents());
            }
            ps.setInt(7, stage);
            ps.setString(8, reviewId);
        });
        broadcast(reviewId);
    }

    /**
     * Record one LLM call in the review's lifetime — the review generation ({@code kind = "review"}) or
     * a conversation follow-up ({@code kind = "followup"}) — for the cost-breakdown UI (roadmap 11).
     * Null-usage safe: a call with no usage (e.g. a legacy follow-up event) records nothing.
     */
    public void recordLlmCall(String reviewId, String kind, ModelUsage usage) {
        if (usage == null) {
            return;
        }
        update("""
                INSERT INTO review_llm_call (id, review_id, kind, model, tokens_in, tokens_out, cost_millicents)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, ps -> {
            ps.setObject(1, java.util.UUID.randomUUID());
            ps.setString(2, reviewId);
            ps.setString(3, kind);
            ps.setString(4, usage.model());
            ps.setInt(5, usage.tokensIn());
            ps.setInt(6, usage.tokensOut());
            ps.setLong(7, usage.costMillicents());
        });
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
     * loc is "path:line" (existing format) split at the LAST ':'. Prefers the entry's own STORED
     * threadRef (set by {@link #recordOpenFindings} for a carried-forward still-open finding) so a
     * finding's original thread survives without needing a {@code review_thread} row; falls back to
     * the path:line thread-index join for a finding that has never been through carry-forward (e.g.
     * this round's brand-new findings, or a first-review row predating {@link #recordOpenFindings}).
     */
    private PriorFinding toPriorFinding(ReviewDetail.FindingView f, ThreadIndex index) {
        int splitAt = f.loc().lastIndexOf(':');
        String path = f.loc().substring(0, splitAt);
        int line = Integer.parseInt(f.loc().substring(splitAt + 1));
        String threadRef = f.threadRef() != null ? f.threadRef() : index.threadByLoc().get(f.loc());
        return new PriorFinding(path, line, severityFromSlug(f.sev()), f.msg(), threadRef);
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

    private ReconciliationEntry toReconciliationEntry(FindingVerdict v, PriorFinding match) {
        String sev = severitySlug(match == null ? Severity.INFO : match.severity());
        String msg = match == null ? "" : match.message();
        return new ReconciliationEntry(sev, v.path() + ":" + v.line(), msg, v.status().name(), v.note(), v.threadRef());
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

    /** Wire shape for {@code reconciliation_json}: one entry per verdict, merged with its finding. */
    private record ReconciliationEntry(String sev, String loc, String msg, String status, String note,
                                       String threadRef) {
    }

    /**
     * The carry-forward baseline for the NEXT follow-up's reconciliation (ADR-019 refinement) —
     * this round's brand-new findings (from {@code result}, threadRef unset — resolved at read time
     * via the thread-index join like a fresh {@code findings_json} row) UNION every prior finding
     * still open after this round's verdicts (STILL_OPEN/UNCHANGED, or no matching verdict at all —
     * carrying an unmatched prior finding is the safer default over silently dropping it). A carried
     * finding keeps its ORIGINAL threadRef/severity/message so it survives even when no
     * {@code review_thread} row exists for its loc. Written to {@code open_findings_json}, encrypted
     * (AAD = reviewId); lenient on serialization failure (WARN + skip), like {@link #recordReconciliation}.
     */
    public void recordOpenFindings(String reviewId, ReviewResult result, List<FindingVerdict> verdicts,
                                   List<PriorFinding> priorFindings) {
        List<ReviewDetail.FindingView> open = new ArrayList<>();
        result.findings().forEach(f -> open.add(toView(f)));
        open.addAll(stillOpenPriorFindings(verdicts, priorFindings));
        List<ReviewDetail.FindingView> deduped = dedupeByAnchor(open);
        String json;
        try {
            json = mapper.writeValueAsString(deduped);
        } catch (JsonProcessingException e) {
            LOG.warn("Failed to serialize open findings", e);
            return;
        }
        String encrypted = encryption.encryptString(json, reviewId);
        update("UPDATE review_status SET open_findings_json = ?, updated_at = now() WHERE review_id = ?", ps -> {
            ps.setString(1, encrypted);
            ps.setString(2, reviewId);
        });
    }

    /**
     * One anchor ({@code path:line}) = one tracked concern. Two findings sharing an anchor collapse
     * onto the same SCM thread at posting time (the second's inline post folds into the first's
     * anchor claim, {@link #attachThreadRefs}); carrying both forward as separate baseline entries
     * makes the NEXT round's verdict matching ambiguous (one threadRef, two prior findings — see
     * {@link #matchVerdict}). Grouping here, before the baseline is written, guarantees every
     * baseline this method produces has unique anchors (and, after the thread join, unique
     * threadRefs) — the group keeps the FIRST entry's severity, the first non-null threadRef found
     * in the group, and concatenates distinct messages with {@code "; also: "}.
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

    /** Collapse one same-anchor group into a single {@link ReviewDetail.FindingView} — see
     *  {@link #dedupeByAnchor} for the merge rules. Message merge is idempotent: merging the same
     *  constituents (in any order, even if already merged) always yields the same result. */
    private static ReviewDetail.FindingView mergeFindingGroup(List<ReviewDetail.FindingView> group) {
        ReviewDetail.FindingView first = group.getFirst();
        String threadRef = group.stream().map(ReviewDetail.FindingView::threadRef)
                .filter(java.util.Objects::nonNull).findFirst().orElse(null);
        String msg = mergeMessages(group.stream().map(ReviewDetail.FindingView::msg).toList());
        return new ReviewDetail.FindingView(first.sev(), first.loc(), msg, threadRef);
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
     * rename fix), while severity/message/threadRef still come from the prior finding, which the
     * verdict does not carry. An unmatched prior finding keeps its own loc, as before.
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
                        loc, pf.message(), pf.threadRef()));
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
     * Permanently delete a review and everything derived from it: the read-model
     * row, its scoped timeline ({@code review_event}) and the underlying event
     * stream ({@code event_log}, keyed by stream_id = reviewId). Done in one
     * transaction so a review never half-vanishes. Returns false when there was
     * no such review; broadcasts a removal so live clients drop the row.
     */
    public boolean deleteReview(String workspace, String slug, long pr) {
        String reviewId = ReviewIds.reviewId(new RepoRef(workspace, slug), pr);
        try (Connection c = dataSource.getConnection()) {
            c.setAutoCommit(false);
            try {
                boolean existed = deleteBy(c, "DELETE FROM review_status WHERE review_id = ?", reviewId) > 0;
                deleteBy(c, "DELETE FROM review_event WHERE review_id = ?", reviewId);
                deleteBy(c, "DELETE FROM event_log WHERE stream_id = ?", reviewId);
                // The worker (separate service, `worker` schema) caches the completed LLM result in
                // comment_idempotency keyed by (review_id, commit) and RE-EMITS it on any redelivery
                // for the same PR@commit — crash-safety so a retry never pays for the LLM twice. But a
                // delete-then-re-register is that same key, so without clearing it here the worker
                // resurrects the stale result and never calls the LLM again (observed: a re-registered
                // review kept showing the old model). Clear it in the same transaction so delete is a
                // true clean slate. Guarded because the worker schema may be absent (worker never
                // started) — its absence must not block deleting an orchestrator review.
                deleteWorkerClaims(c, reviewId);
                c.commit();
                if (existed) {
                    broadcastRemoval(reviewId);
                }
                return existed;
            } catch (SQLException e) {
                c.rollback();
                throw e;
            } finally {
                c.setAutoCommit(true);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete review " + reviewId, e);
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
     * (worker never started / migrated). Shared by {@link #deleteReview} (full delete) and
     * {@link #clearWorkerIdempotency} (re-run): both must leave no worker orphans behind.
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

    /** Reset the row to an in-progress state for a manual re-run, clearing any prior terminal error. */
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

    public List<ReviewSummary> listSummaries() {
        // The LLM vendor for the badge comes from the catalog (name -> type); a scalar subquery keeps
        // it one-row-per-review and yields NULL for uncatalogued models (shown as a neutral chip).
        // total_cost_millicents sums every review_llm_call row (the review generation plus every
        // reconcile/follow-up call), falling back to the last run's cost_millicents for a review that
        // predates per-call tracking — the list row must show the review's LIFETIME cost, not just the
        // last run's (fixes #2).
        String sql = """
                SELECT rs.*, (SELECT m.type FROM llm_model m WHERE m.name = rs.model LIMIT 1) AS llm_type,
                       COALESCE((SELECT SUM(c.cost_millicents) FROM review_llm_call c
                                 WHERE c.review_id = rs.review_id), rs.cost_millicents) AS total_cost_millicents
                FROM review_status rs ORDER BY rs.updated_at DESC
                """;
        List<ReviewSummary> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                out.add(toSummary(rs));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list reviews", e);
        }
        return out;
    }

    public Optional<ReviewDetail> loadDetail(String workspace, String slug, long pr) {
        String reviewId = ReviewIds.reviewId(new RepoRef(workspace, slug), pr);
        try (Connection c = dataSource.getConnection()) {
            ReviewRow row = loadRow(c, reviewId);
            if (row == null) {
                return Optional.empty();
            }
            ThreadIndex threadIndex = buildThreadIndex(loadThreadRows(c, reviewId));
            FindingsWithThreads attached = attachThreadRefs(parseFindings(row.findingsJson, row.id), threadIndex);
            Function<String, String> classifier = threadClassifier(attached.findingRefs(), threadIndex.summaryRefs());
            List<ReviewDetail.ReconciliationView> reconciliation =
                    parseReconciliation(row.reconciliationJson, row.id, threadIndex.resolvedRefs());

            return Optional.of(toDetail(row, loadEvents(c, reviewId, row.createdAt, classifier),
                    llmCalls(reviewId), attached.findings(), reconciliation));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load review " + reviewId, e);
        }
    }

    /** loc "path:line" -> threadRef (first wins on a same-line collision), which threadRefs belong
     *  to a summary comment, and which threadRefs are resolved on the SCM side — all derived once
     *  per {@link #loadDetail} / {@link #priorRunFor} call. */
    private record ThreadIndex(Map<String, String> threadByLoc, Set<String> summaryRefs, Set<String> resolvedRefs) {}

    private ThreadIndex buildThreadIndex(List<ThreadRow> threadRows) {
        Map<String, String> threadByLoc = new HashMap<>();
        Set<String> summaryRefs = new HashSet<>();
        Set<String> resolvedRefs = new HashSet<>();
        for (ThreadRow t : threadRows) {
            if (t.isSummary()) {
                summaryRefs.add(t.threadRef());
            }
            if (t.resolved()) {
                resolvedRefs.add(t.threadRef());
            }
            if (t.path() != null && t.line() != null) {
                // Deterministic tie-break by thread_ref order (loadThreadRows' ORDER BY) for the rare
                // same-line collision — either thread is a valid nesting; what matters is that a
                // review renders the same way every load.
                threadByLoc.putIfAbsent(t.path() + ":" + t.line(), t.threadRef());
            }
        }
        return new ThreadIndex(threadByLoc, summaryRefs, resolvedRefs);
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
                findings.add(new ReviewDetail.FindingView(f.sev(), f.loc(), f.msg(), ref));
            } else {
                findings.add(new ReviewDetail.FindingView(f.sev(), f.loc(), f.msg(), null));
            }
        }
        return new FindingsWithThreads(findings, findingRefs);
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

    /** Every LLM call recorded for a review (the generation + each follow-up), oldest first — the raw
     * material for the cost-breakdown UI (roadmap 11). */
    public List<ReviewDetail.LlmCall> llmCalls(String reviewId) {
        List<ReviewDetail.LlmCall> out = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT kind, model, tokens_in, tokens_out, cost_millicents, created_at FROM review_llm_call "
                             + "WHERE review_id = ? ORDER BY created_at")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ReviewDetail.LlmCall(rs.getString("kind"), rs.getString("model"),
                            rs.getInt("tokens_in"), rs.getInt("tokens_out"), rs.getLong("cost_millicents"),
                            rs.getTimestamp("created_at").toInstant().toString()));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load LLM calls for " + reviewId, e);
        }
        return out;
    }

    // ---- broadcast ---------------------------------------------------------

    private void broadcast(String reviewId) {
        ReviewSummary summary;
        try (Connection c = dataSource.getConnection()) {
            ReviewRow row = loadRow(c, reviewId);
            if (row == null) {
                return; // header not written yet (events can race ahead) — nothing to push
            }
            summary = row.toSummary(llmTypeFor(c, row.model()), openCounts(row), cumulativeCost(c, row));
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
                .filter(conn -> conn.handshakeRequest().path().endsWith("/ws/reviews"))
                .forEach(conn -> conn.sendText(json).subscribe().with(v -> {
                }, t -> LOG.debugf("WS push failed: %s", t.getMessage())));
    }

    // ---- row mapping -------------------------------------------------------

    private record ReviewRow(String id, String workspace, String slug, long pr, String title, String author,
                             String authorId, String branch, String base, String sha, String htmlUrl,
                             String providerType, String status, int stage, int findings, String findingsJson,
                             String reconciliationJson, String model, Integer tokensIn, Integer tokensOut,
                             Long costMillicents, String note, String errorDetail, int attempt, Instant createdAt,
                             Instant updatedAt) {
        ReviewSummary toSummary(String llmType, OpenCounts openCounts, long totalCostMillicents) {
            return new ReviewSummary(id, workspace, slug, slug, pr, title, author, authorId, branch, base, sha,
                    htmlUrl, providerType, status, stage, openCounts.open(), openCounts.openBlockers(),
                    totalCostMillicents, model == null ? "" : model,
                    llmType == null ? "" : llmType, updatedAt);
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
        return row.toSummary(rs.getString("llm_type"), openCounts(row), rs.getLong("total_cost_millicents"));
    }

    /** Count of blocker-severity (critical) findings on a row — drives the detail page's
     *  current-run "changes requested" outcome ({@link #toDetail}). */
    private int blockerCount(ReviewRow row) {
        return (int) parseFindings(row.findingsJson(), row.id()).stream()
                .filter(f -> "critical".equals(f.sev()))
                .count();
    }

    private record OpenCounts(int open, int openBlockers) {
    }

    /**
     * The review's currently-open findings — this run's new findings plus reconciliation
     * entries still open (still-open/unchanged), deduped by thread. This is what the list row
     * must show (fixes #3): a review with a carried-forward open critical is NOT "passed".
     */
    private OpenCounts openCounts(ReviewRow row) {
        Map<String, String> openSevByKey = new java.util.LinkedHashMap<>();
        for (ReviewDetail.FindingView f : parseFindings(row.findingsJson(), row.id())) {
            openSevByKey.put(keyOf(f.threadRef(), f.loc()), f.sev());
        }
        for (ReviewDetail.ReconciliationView r : parseReconciliation(row.reconciliationJson(), row.id(), Set.of())) {
            if ("still open".equals(r.status()) || "unchanged".equals(r.status())) {
                openSevByKey.put(keyOf(r.threadRef(), r.loc()), r.sev());
            }
        }
        int blockers = (int) openSevByKey.values().stream().filter("critical"::equals).count();
        return new OpenCounts(openSevByKey.size(), blockers);
    }

    private static String keyOf(String threadRef, String loc) {
        return threadRef != null && !threadRef.isBlank() ? "t:" + threadRef : "l:" + loc;
    }

    /**
     * Cumulative cost for a single row loaded outside {@link #listSummaries}'s subquery (the
     * {@link #broadcast} path) — same fallback rule as the SQL: sum every {@code review_llm_call}
     * row, falling back to the row's own {@code cost_millicents} when there are none yet.
     */
    private long cumulativeCost(Connection c, ReviewRow row) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT SUM(cost_millicents) FROM review_llm_call WHERE review_id = ?")) {
            ps.setString(1, row.id());
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    long sum = rs.getLong(1);
                    if (!rs.wasNull()) {
                        return sum;
                    }
                }
            }
        }
        return row.costMillicents() == null ? 0L : row.costMillicents();
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
                rs.getString("status"), rs.getInt("stage"), rs.getInt("findings_count"),
                rs.getString("findings_json"), rs.getString("reconciliation_json"), rs.getString("model"),
                (Integer) rs.getObject("tokens_in"), (Integer) rs.getObject("tokens_out"),
                (Long) rs.getObject("cost_millicents"), rs.getString("note"), rs.getString("error_detail"),
                rs.getInt("attempt"),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant());
    }

    private ReviewDetail toDetail(ReviewRow r, List<ReviewDetail.EventView> events, List<ReviewDetail.LlmCall> llmCalls,
                                  List<ReviewDetail.FindingView> findings,
                                  List<ReviewDetail.ReconciliationView> reconciliation) {
        return new ReviewDetail(r.id, r.workspace, r.slug, r.slug, r.pr, r.title, r.author, r.authorId,
                r.branch, r.base, r.sha, r.htmlUrl, r.providerType, r.status, r.stage, r.findings, blockerCount(r),
                r.updatedAt, r.attempt, computeStages(r.status, r.stage), List.of("", "", "", "", "", ""),
                findings, reconciliation, usageView(r), withReviewCall(r, llmCalls), r.note,
                decryptError(r.errorDetail, r.id), events);
    }

    /**
     * Guarantee the review's own generation call leads the cost breakdown. Reviews created before
     * per-call tracking ({@code review_llm_call}) existed hold their usage only on the review_status
     * row; once follow-ups add rows, the breakdown would render those but silently drop the initial
     * review. Synthesize the missing {@code review} call from the stored review usage — the same real
     * figures already surfaced as the legacy single-usage view — and put it first (it ran first).
     */
    private List<ReviewDetail.LlmCall> withReviewCall(ReviewRow r, List<ReviewDetail.LlmCall> calls) {
        boolean hasReview = calls.stream().anyMatch(c -> "review".equals(c.kind()));
        if (hasReview || r.model == null) {
            return calls;
        }
        ReviewDetail.LlmCall reviewCall = new ReviewDetail.LlmCall("review", r.model,
                r.tokensIn == null ? 0 : r.tokensIn, r.tokensOut == null ? 0 : r.tokensOut,
                r.costMillicents == null ? 0L : r.costMillicents,
                r.createdAt == null ? null : r.createdAt.toString());
        List<ReviewDetail.LlmCall> merged = new ArrayList<>(calls.size() + 1);
        merged.add(reviewCall);
        merged.addAll(calls);
        return merged;
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

    private record ThreadRow(String threadRef, String path, Integer line, boolean isSummary, boolean resolved) {}

    private List<ThreadRow> loadThreadRows(Connection c, String reviewId) throws SQLException {
        List<ThreadRow> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT thread_ref, path, line, is_summary, resolved FROM review_thread WHERE review_id = ? "
                        + "ORDER BY thread_ref")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(new ThreadRow(rs.getString("thread_ref"), rs.getString("path"),
                            (Integer) rs.getObject("line"), rs.getBoolean("is_summary"), rs.getBoolean("resolved")));
                }
            }
        }
        return out;
    }

    private List<ReviewDetail.EventView> loadEvents(Connection c, String reviewId, Instant t0,
            Function<String, String> threadKind) throws SQLException {
        List<ReviewDetail.EventView> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT lane, type, detail, at, thread_ref FROM review_event WHERE review_id = ? ORDER BY seq")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    Instant at = rs.getTimestamp("at").toInstant();
                    String threadRef = rs.getString("thread_ref");
                    out.add(new ReviewDetail.EventView(at.toString(), relative(t0, at), rs.getString("lane"),
                            rs.getString("type"), rs.getString("detail"), threadRef, threadKind.apply(threadRef)));
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
            case "cancelled", "superseded" -> {
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

    private ReviewDetail.UsageView usageView(ReviewRow r) {
        if (r.model == null) {
            return null;
        }
        String cost = r.costMillicents == null ? "—"
                : String.format(java.util.Locale.ROOT, "$%.3f", r.costMillicents / 100_000.0);
        return new ReviewDetail.UsageView(r.model,
                r.tokensIn == null ? "—" : String.format(java.util.Locale.ROOT, "%,d", r.tokensIn),
                r.tokensOut == null ? "—" : String.format(java.util.Locale.ROOT, "%,d", r.tokensOut),
                cost, "—");
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
                            statusDisplay(e.status()), e.note(), e.threadRef(), resolvedRefs.contains(e.threadRef())))
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
