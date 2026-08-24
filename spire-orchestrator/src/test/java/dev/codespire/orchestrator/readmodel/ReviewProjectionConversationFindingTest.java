package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.Severity;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.encryption.EncryptionService;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A finding raised from a conversation ({@code /finding}) joins the carry-forward baseline
 * ({@code open_findings_json}) rather than a fresh {@code findings_json} row — see
 * {@link ReviewProjection#addConversationFinding} for why.
 */
@QuarkusTest
@TestSecurity(user = "test-admin", roles = {"spire-viewer", "spire-admin"})
class ReviewProjectionConversationFindingTest {

    @Inject
    ReviewProjection projection;

    @Inject
    DataSource dataSource;

    @Inject
    EncryptionService encryption;

    @Inject
    ReviewThreadView threads;

    @Test
    void aConversationFindingJoinsTheCarryForwardBaseline() {
        String reviewId = registerReviewWithOpenFindings("src/Bar.java:10", "warning");

        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.MINOR, "shadows the field");

        PriorRun prior = projection.priorRunFor(reviewId).orElseThrow();
        assertTrue(prior.findings().stream()
                        .anyMatch(f -> "src/Foo.java".equals(f.path()) && f.line() == 44),
                "the carry-forward baseline must include the conversation finding");
    }

    @Test
    void aConversationFindingOnAnAlreadyFlaggedLineMergesRatherThanDoubling() {
        // dedupeByAnchor already enforces one anchor = one tracked concern. Nothing in the new code
        // fails if that stops working, which is exactly why it is asserted here.
        String reviewId = registerReviewWithOpenFindings("src/Foo.java:44", "warning");

        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.MINOR, "and it also shadows the field");

        List<ReviewDetail.FindingView> atAnchor = projection.openFindingsFor(reviewId).stream()
                .filter(f -> "src/Foo.java:44".equals(f.loc())).toList();
        assertEquals(1, atAnchor.size(), "one anchor must stay one tracked concern");
        assertTrue(atAnchor.getFirst().msg().contains("also shadows the field"));
        assertNull(atAnchor.getFirst().origin(),
                "the concern was already tracked (review-derived) before the human spoke; a later "
                        + "conversation reply on the same anchor must not relabel it human-filed");
    }

    @Test
    void aStoredRowWrittenBeforeOriginExistedReadsBackAsReviewDerived() throws SQLException {
        // A genuine pre-migration row: hand-written JSON with only the four legacy FindingView keys
        // and no "origin" key at all -- distinct from anything the current code would ever write,
        // which always serializes "origin":null explicitly. Jackson must still read this back as
        // review-derived (null), not fail to parse it.
        long pr = ReviewFixtures.newPr();
        String reviewId = ReviewFixtures.reviewIdFor(pr);
        projection.registerHeader(reviewId, ReviewFixtures.REPO_REF, pr, "TEST-TITLE", "TEST-AUTHOR",
                "TEST-AUTHOR-ID", "TEST-SOURCE", "TEST-TARGET", "TESTSHA" + pr,
                "http://example.invalid/pr/" + pr, "github", "reviewing", 0);
        String legacyJson = "[{\"sev\":\"warning\",\"loc\":\"src/Bar.java:10\",\"msg\":\"seed finding\","
                + "\"threadRef\":null}]";
        writeRawOpenFindings(reviewId, legacyJson);

        List<ReviewDetail.FindingView> open = projection.openFindingsFor(reviewId);

        assertFalse(open.isEmpty());
        assertNull(open.getFirst().origin());
    }

    @Test
    void aConversationFindingIsMarkedAsOne() {
        String reviewId = registerReviewWithOpenFindings("src/Bar.java:10", "warning");

        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.MINOR, "shadows the field");

        ReviewDetail.FindingView added = projection.openFindingsFor(reviewId).stream()
                .filter(f -> "src/Foo.java:44".equals(f.loc())).findFirst().orElseThrow();
        assertEquals("conversation", added.origin());
    }

    /**
     * A round in flight carries the PriorRun snapshot taken when its command was dispatched. A
     * {@code /finding} filed after that snapshot but before the round completes is in neither the
     * round's own result nor its verdicts/priorFindings — so {@code recordOpenFindings}, which
     * REPLACES {@code open_findings_json} wholesale from exactly those two inputs, must not silently
     * drop it just because this round's command never carried it.
     */
    @Test
    void aConversationFindingFiledMidRoundSurvivesTheRoundsCompletion() {
        String reviewId = registerReviewWithOpenFindings("src/Bar.java:10", "warning");

        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.MINOR, "shadows the field");

        // The round completes with a result/verdicts that never mention the finding just filed —
        // exactly what a command dispatched before it existed would carry.
        ReviewResult result2 = new ReviewResult(List.of(), "TEST-SUMMARY-2", ModelUsage.of("TEST-MODEL", 1, 1));
        projection.recordOpenFindings(reviewId, result2, List.of(), List.of());

        ReviewDetail.FindingView survivor = projection.openFindingsFor(reviewId).stream()
                .filter(f -> "src/Foo.java:44".equals(f.loc())).findFirst().orElseThrow(
                        () -> new AssertionError("conversation finding filed mid-round was dropped"));
        assertEquals("conversation", survivor.origin());
    }

    /**
     * {@code addConversationFinding} must never write nothing to {@code posted_findings_json} for a
     * review that has never been posted — {@link ReviewProjection#priorRunFor} returns
     * {@code Optional.empty()} while {@code last_posted_commit IS NULL}, so nothing reads that column
     * yet and writing one would invent a snapshot that was never actually posted.
     */
    @Test
    void addConversationFindingSkipsThePostedSnapshotWhenNeverPosted() throws SQLException {
        long pr = ReviewFixtures.newPr();
        String reviewId = ReviewFixtures.reviewIdFor(pr);
        projection.registerHeader(reviewId, ReviewFixtures.REPO_REF, pr, "TEST-TITLE", "TEST-AUTHOR",
                "TEST-AUTHOR-ID", "TEST-SOURCE", "TEST-TARGET", "TESTSHA" + pr,
                "http://example.invalid/pr/" + pr, "github", "reviewing", 0);
        // No recordPosted -- last_posted_commit stays NULL.

        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.MINOR, "shadows the field");

        // The column check is the real discrimination: priorRunFor returns empty whenever
        // last_posted_commit is null REGARDLESS of what posted_findings_json holds, so asserting
        // against priorRunFor alone would pass even if the skip were deleted.
        assertNull(rawPostedFindingsJson(reviewId),
                "posted_findings_json must not be written for a review that has never been posted");
        assertTrue(projection.priorRunFor(reviewId).isEmpty(),
                "posted_findings_json must not be invented for a review that has never been posted");
    }

    private String rawPostedFindingsJson(String reviewId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT posted_findings_json FROM review_status WHERE review_id = ?")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString("posted_findings_json");
            }
        }
    }

    /**
     * A decrypt/parse failure on {@code posted_findings_json} must not turn a conversation finding
     * into a destructive overwrite. {@code parseFindings}'s ordinary degrade-to-empty-list posture is
     * correct everywhere else in the file, but {@code addConversationFinding}'s merge is a
     * read-modify-write: blindly merging into an empty list would REPLACE whatever was actually
     * stored with just this one new finding. {@code open_findings_json} must still be updated —
     * one column's corruption must not block the other.
     */
    @Test
    void addConversationFindingSkipsAnUnparseablePostedColumnRatherThanDestroyingIt() throws SQLException {
        String reviewId = registerReviewWithOpenFindings("src/Bar.java:10", "warning");
        corruptPostedFindingsJson(reviewId);
        String corrupted = rawPostedFindingsJson(reviewId);

        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.MINOR, "shadows the field");

        assertEquals(corrupted, rawPostedFindingsJson(reviewId),
                "a decrypt/parse failure must skip the write, not replace the column with just the "
                        + "new finding");
        assertTrue(projection.openFindingsFor(reviewId).stream()
                        .anyMatch(f -> "src/Foo.java:44".equals(f.loc())),
                "open_findings_json must still be updated even though posted_findings_json could not be");
    }

    /** Writes a value that is neither valid Tink ciphertext for this reviewId nor valid legacy-plaintext
     *  JSON, into {@code posted_findings_json} — simulating real corruption or a decrypt failure. */
    private void corruptPostedFindingsJson(String reviewId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE review_status SET posted_findings_json = ? WHERE review_id = ?")) {
            ps.setString(1, "NOT-VALID-CIPHERTEXT-OR-JSON");
            ps.setString(2, reviewId);
            ps.executeUpdate();
        }
    }

    /**
     * {@code recordPosted} guards its write with {@code WHERE commit_sha = ?} so a superseded round
     * can never pair the PREVIOUS round's {@code last_posted_commit} with newer findings.
     * {@code addConversationFinding} must not bypass that guard by copying {@code open_findings_json}
     * straight over {@code posted_findings_json} — here, round 2's {@code recordOpenFindings} has run
     * (so {@code open_findings_json} already reflects round 2's own, still-unposted baseline) but
     * round 2's {@code recordPosted} has NOT, so {@code posted_findings_json} must still be round 1's.
     * A conversation finding filed in that window must amend round 1's posted snapshot in place, not
     * promote round 2's unposted one.
     */
    @Test
    void addConversationFindingAmendsThePostedSnapshotWithoutPromotingAnUnpostedBaseline() {
        String reviewId = registerReviewWithOpenFindings("src/Bar.java:10", "warning");

        // Round 2's recordOpenFindings runs but its recordPosted deliberately does not -- the exact
        // window Important 1 protects. priorFindings=List.of() means round 2 does not carry round
        // 1's Bar.java:10 forward, so open_findings_json now visibly diverges from posted_findings_json.
        ReviewResult result2 = new ReviewResult(
                List.of(new Finding("src/Baz.java", new LineRange(1, 1), Severity.MAJOR, "new issue", null)),
                "TEST-SUMMARY-2", ModelUsage.of("TEST-MODEL", 1, 1));
        projection.recordOpenFindings(reviewId, result2, List.of(), List.of());

        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.MINOR, "shadows the field");

        List<PriorFinding> posted = projection.priorRunFor(reviewId).orElseThrow().findings();
        assertTrue(posted.stream().anyMatch(pf -> "src/Bar.java".equals(pf.path()) && pf.line() == 10),
                "the posted snapshot must keep round 1's ACTUALLY POSTED finding");
        assertTrue(posted.stream().anyMatch(pf -> "src/Foo.java".equals(pf.path()) && pf.line() == 44),
                "the conversation finding must be amended into the posted snapshot");
        assertFalse(posted.stream().anyMatch(pf -> "src/Baz.java".equals(pf.path())),
                "round 2's new but UNPOSTED finding must not be promoted into the posted snapshot");
    }

    /**
     * A conversation finding filed BEFORE a round's {@code PriorRun} snapshot is taken is, by that
     * round, a plain {@code PriorFinding} — {@code PriorFinding} carries its own {@code origin},
     * copied from the posted snapshot's {@code FindingView} at {@code toPriorFinding}. If that
     * round's verdicts never judge it, it is carried forward by BOTH {@code stillOpenPriorFindings}
     * (via {@code PriorFinding#origin()} — the null-status "unmatched" branch) AND
     * {@code unmatchedConversationFindings} (still tagged {@code "conversation"} in the CURRENT
     * {@code open_findings_json}). Two same-anchor entries collapse to one at
     * {@code dedupeByAnchor}; both already carry the tag, so it survives regardless of which one
     * the merge keeps.
     */
    @Test
    void aConversationFindingCarriedAsAPriorFindingKeepsItsOriginAcrossAReconciliationRound() {
        String reviewId = registerReviewWithOpenFindings("src/Bar.java:10", "warning");

        // Filed before round 2's snapshot: last_posted_commit is already set (round 1's
        // recordPosted), so this also lands in posted_findings_json and becomes a real
        // PriorFinding for round 2.
        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.MINOR, "shadows the field");
        List<PriorFinding> priorFindings = projection.priorRunFor(reviewId).orElseThrow().findings();
        assertTrue(priorFindings.stream().anyMatch(pf -> "src/Foo.java".equals(pf.path()) && pf.line() == 44),
                "setup check: the conversation finding must have promoted into the prior run");

        // Round 2 completes with no verdict at all for src/Foo.java:44 -- unmatched, so both
        // stillOpenPriorFindings and unmatchedConversationFindings carry it forward.
        ReviewResult result2 = new ReviewResult(List.of(), "TEST-SUMMARY-2", ModelUsage.of("TEST-MODEL", 1, 1));
        projection.recordOpenFindings(reviewId, result2, List.of(), priorFindings);

        ReviewDetail.FindingView survivor = projection.openFindingsFor(reviewId).stream()
                .filter(f -> "src/Foo.java:44".equals(f.loc())).findFirst().orElseThrow();
        assertEquals("conversation", survivor.origin(),
                "origin must not silently flip to review-derived across a reconciliation round");
    }

    /**
     * A conversation finding's loc is not permanently "conversation territory": once its own verdict
     * resolves it, that anchor is vacated, and a fresh review-derived finding that lands there next
     * round is a genuinely NEW, unrelated concern. An origin re-tag that decided by loc/threadRef
     * membership against the PRE-round baseline could not tell the two apart — the vacated loc was
     * still in its "conversation locs" set, so the new finding inherited the tag it had no right to.
     * Origin sourced per-entry (from the finding's own history) does not have this failure mode: a
     * brand-new {@code toView} finding is null-origin by construction, independent of what any other
     * entry at that loc used to be.
     */
    @Test
    void aNewFindingOnAVacatedConversationLocIsNotMislabelledAsConversation() {
        String reviewId = registerReviewWithOpenFindings("src/Bar.java:10", "warning");

        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.MINOR, "shadows the field");
        List<PriorFinding> priorFindings = projection.priorRunFor(reviewId).orElseThrow().findings();

        // Round 2: the human's concern is judged RESOLVED (excluded from the carry-forward baseline),
        // and the fresh review reports an unrelated new defect that happens to land on the very same
        // anchor the resolved conversation finding just vacated.
        List<FindingVerdict> verdicts = List.of(
                new FindingVerdict("t-900", "src/Foo.java", 44, FindingVerdict.Status.RESOLVED, "fixed"));
        ReviewResult result2 = new ReviewResult(
                List.of(new Finding("src/Foo.java", new LineRange(44, 44), Severity.MAJOR,
                        "an unrelated new defect", null)),
                "TEST-SUMMARY-2", ModelUsage.of("TEST-MODEL", 1, 1));

        projection.recordOpenFindings(reviewId, result2, verdicts, priorFindings);

        ReviewDetail.FindingView survivor = projection.openFindingsFor(reviewId).stream()
                .filter(f -> "src/Foo.java:44".equals(f.loc())).findFirst().orElseThrow();
        assertEquals("an unrelated new defect", survivor.msg());
        assertNull(survivor.origin(),
                "a brand-new, model-reported finding must not inherit a resolved conversation finding's "
                        + "origin just because it landed on the same now-vacated anchor");
    }

    /**
     * A finding re-posted at one loc across rounds ({@code review_thread}'s "newest row wins" rule,
     * exercised directly in {@code ReviewProjectionPriorRunIT}) makes {@code toPriorFinding} hand back
     * a DIFFERENT threadRef than the conversation finding's own — and combined with a rename, its loc
     * moves too. An origin re-tag keyed on the pre-round baseline's loc/threadRef could not follow
     * either move and silently dropped the tag. Sourcing origin from {@link PriorFinding#origin()}
     * itself is immune: it was copied at construction, before any of that substitution happened.
     */
    @Test
    void aCarriedConversationFindingKeepsItsOriginDespiteASupersededThreadAndARename() {
        String reviewId = registerReviewWithOpenFindings("src/Bar.java:10", "warning");

        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.MINOR, "shadows the field");
        // A later post at the SAME loc (e.g. a re-review's own finding landing there) leaves a newer
        // review_thread row for src/Foo.java:44 -- "newest row wins" now points away from t-900.
        threads.markFindingThread(reviewId, new ThreadRef("newer-thread"), "src/Foo.java", 44);

        List<PriorFinding> priorFindings = projection.priorRunFor(reviewId).orElseThrow().findings();
        PriorFinding conversationFinding = priorFindings.stream()
                .filter(pf -> "src/Foo.java".equals(pf.path()) && pf.line() == 44).findFirst().orElseThrow();
        assertEquals("newer-thread", conversationFinding.threadRef(),
                "setup check: the newer thread must have superseded the finding's own t-900");
        assertEquals("conversation", conversationFinding.origin(),
                "setup check: origin must survive the thread substitution onto the PriorFinding itself");

        // Round 2: a verdict matches via the SUPERSEDED thread and reports a rename -- both the loc
        // AND the threadRef this finding is carried forward under now differ from its ORIGINAL anchor.
        List<FindingVerdict> verdicts = List.of(
                new FindingVerdict("newer-thread", "src/Foo2.java", 50,
                        FindingVerdict.Status.STILL_OPEN, "still there"));
        ReviewResult result2 = new ReviewResult(List.of(), "TEST-SUMMARY-2", ModelUsage.of("TEST-MODEL", 1, 1));

        projection.recordOpenFindings(reviewId, result2, verdicts, priorFindings);

        ReviewDetail.FindingView survivor = projection.openFindingsFor(reviewId).stream()
                .filter(f -> "src/Foo2.java:50".equals(f.loc())).findFirst().orElseThrow();
        assertEquals("conversation", survivor.origin(),
                "origin must survive even though neither loc nor threadRef matches the finding's "
                        + "original anchor");
    }

    private void writeRawOpenFindings(String reviewId, String plaintextJson) throws SQLException {
        String encrypted = encryption.encryptString(plaintextJson, reviewId);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE review_status SET open_findings_json = ? WHERE review_id = ?")) {
            ps.setString(1, encrypted);
            ps.setString(2, reviewId);
            ps.executeUpdate();
        }
    }

    /**
     * Register a review with one open finding already tracked at {@code loc}, posted so
     * {@link ReviewProjection#priorRunFor} has a baseline to reconcile against — built on the
     * projection's own write API (register -> recordOutcome -> recordOpenFindings -> recordPosted),
     * the same sequence {@code ResultSaga} runs for a real round.
     */
    private String registerReviewWithOpenFindings(String loc, String sevSlug) {
        long pr = ReviewFixtures.newPr();
        String reviewId = ReviewFixtures.reviewIdFor(pr);
        String commit = "TESTSHA" + pr;
        projection.registerHeader(reviewId, ReviewFixtures.REPO_REF, pr, "TEST-TITLE", "TEST-AUTHOR",
                "TEST-AUTHOR-ID", "TEST-SOURCE", "TEST-TARGET", commit,
                "http://example.invalid/pr/" + pr, "github", "reviewing", 0);

        int splitAt = loc.lastIndexOf(':');
        String path = loc.substring(0, splitAt);
        int line = Integer.parseInt(loc.substring(splitAt + 1));
        ReviewResult result = new ReviewResult(
                List.of(new Finding(path, new LineRange(line, line), severityFor(sevSlug), "seed finding", null)),
                "TEST-SUMMARY", ModelUsage.of("TEST-MODEL", 1, 1));
        projection.recordOutcome(reviewId, result, ReviewProjection.STAGE_COMMENTS);
        projection.recordOpenFindings(reviewId, result, List.of(), List.of());
        projection.recordPosted(reviewId, commit, "TEST-SUM-" + pr);
        return reviewId;
    }

    private static Severity severityFor(String slug) {
        return switch (slug) {
            case "critical" -> Severity.BLOCKER;
            case "warning" -> Severity.MAJOR;
            case "suggestion" -> Severity.MINOR;
            default -> Severity.INFO;
        };
    }
}
