package dev.codespire.orchestrator.readmodel;

import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.LineRange;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.Severity;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.orchestrator.attention.AttentionBroadcaster;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import io.quarkus.websockets.next.CloseReason;
import io.quarkus.websockets.next.HandshakeRequest;
import io.quarkus.websockets.next.OpenConnections;
import io.quarkus.websockets.next.UserData;
import io.quarkus.websockets.next.WebSocketConnection;
import io.smallrye.mutiny.Uni;
import io.vertx.core.buffer.Buffer;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

import javax.net.ssl.SSLSession;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A finding raised from a conversation ({@code /finding}) joins the carry-forward baseline
 * ({@code open_findings_json}) rather than a fresh {@code findings_json} row — see
 * {@link ReviewProjection#addConversationFinding} for why.
 *
 * <p>Which is why the dashboard's findings card is a UNION of two columns
 * ({@link ReviewProjection#loadDetail}): storing the finding somewhere the card never read is what
 * made it invisible in every round. The second half of this suite is that union — the card itself,
 * the open/blocker counts, the origin surviving onto a reconciliation row, and the one anchor both
 * columns can name at once.
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
    ObjectMapper mapper;

    @Inject
    AttentionBroadcaster attention;

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

    /**
     * S1 defense in depth: repeated {@code /finding} calls at the SAME anchor, each carrying a
     * distinct message, must not grow the merged message without bound — the growth vector the
     * security review flagged as reachable by any PR commenter, since {@code authorAllowed} defaults
     * to true when a provider sets no allowlist. A single message is already capped at parse time
     * ({@code ConversationFindings.MAX_MESSAGE_LENGTH}); this asserts the merge itself has its own
     * ceiling, independent of that upstream cap.
     */
    @Test
    void repeatedFindingsAtOneAnchorDoNotGrowTheMergedMessageWithoutBound() {
        String reviewId = registerReviewWithOpenFindings("src/Bar.java:10", "warning");

        for (int i = 0; i < 10; i++) {
            String distinct = "distinct message #" + i + " " + "y".repeat(4_000);
            projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                    Severity.MINOR, distinct);
        }

        ReviewDetail.FindingView merged = projection.openFindingsFor(reviewId).stream()
                .filter(f -> "src/Foo.java:44".equals(f.loc())).findFirst().orElseThrow();
        assertTrue(merged.msg().length() <= ReviewProjection.MAX_MERGED_MESSAGE_LENGTH,
                "the merged message must stay bounded no matter how many distinct /finding calls "
                        + "land on one anchor");
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

    /**
     * L2: {@code recordOpenFindings} REPLACES {@code open_findings_json} wholesale, and
     * {@code unmatchedConversationFindings} depends entirely on reading it — so a decrypt/parse
     * failure there must skip the write, exactly like {@code mergeColumnOrSkip} already does on the
     * {@code /finding} write path. Before this, {@code parseFindings}'s ordinary
     * degrade-to-empty-list posture would silently destroy every human-filed finding on the round.
     */
    @Test
    void recordOpenFindingsSkipsAnUnparseableOpenColumnRatherThanDestroyingIt() throws SQLException {
        String reviewId = registerReviewWithOpenFindings("src/Bar.java:10", "warning");
        corruptOpenFindingsJson(reviewId);
        String corrupted = rawOpenFindingsJson(reviewId);

        ReviewResult result2 = new ReviewResult(
                List.of(new Finding("src/Baz.java", new LineRange(1, 1), Severity.MAJOR, "new issue", null)),
                "TEST-SUMMARY-2", ModelUsage.of("TEST-MODEL", 1, 1));
        projection.recordOpenFindings(reviewId, result2, List.of(), List.of());

        assertEquals(corrupted, rawOpenFindingsJson(reviewId),
                "a decrypt/parse failure must skip the write, not silently replace the baseline");
    }

    private String rawOpenFindingsJson(String reviewId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT open_findings_json FROM review_status WHERE review_id = ?")) {
            ps.setString(1, reviewId);
            try (ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getString("open_findings_json");
            }
        }
    }

    /** Writes a value that is neither valid Tink ciphertext for this reviewId nor valid legacy-plaintext
     *  JSON into {@code open_findings_json} — simulating real corruption or a decrypt failure. */
    private void corruptOpenFindingsJson(String reviewId) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE review_status SET open_findings_json = ? WHERE review_id = ?")) {
            ps.setString(1, "NOT-VALID-CIPHERTEXT-OR-JSON");
            ps.setString(2, reviewId);
            ps.executeUpdate();
        }
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

    /**
     * M1: none of {@code writeOpenOnly}/{@code writeOpenAndPosted}/{@code writePostedOnly} used to
     * broadcast, so the findings card and open count stayed stale until some unrelated write
     * happened to push a fresh summary. {@code review_status.updated_at} is already bumped by the
     * raw {@code UPDATE} itself regardless of whether a broadcast happens, so reading it back
     * (through {@code loadDetail} or {@code listSummaries}) would pass even on the unfixed code and
     * prove nothing — the only thing that discriminates this bug is whether a client actually
     * connected to the reviews socket is pushed a message, so this fakes that socket the way a real
     * client would see it, rather than polling the database.
     *
     * <p>Built as a fresh instance with the real collaborators rather than mutating the injected
     * {@code projection}'s {@code connections} field: {@code projection} is a CDI client proxy, and a
     * plain field assignment on it lands on the proxy object itself, not on the contextual instance
     * every business method call actually delegates to — so the fake would silently never be seen by
     * {@code broadcast()}.
     */
    @Test
    void addConversationFindingPushesTheFindingToLiveClients() {
        String reviewId = registerReviewWithOpenFindings("src/Bar.java:10", "warning");
        List<String> pushed = new ArrayList<>();
        ReviewProjection withFakeSocket = new ReviewProjection();
        withFakeSocket.dataSource = dataSource;
        withFakeSocket.mapper = mapper;
        withFakeSocket.encryption = encryption;
        withFakeSocket.attention = attention;
        withFakeSocket.connections = new OpenConnections() {
            @Override
            public java.util.stream.Stream<WebSocketConnection> stream() {
                return java.util.stream.Stream.of(new FakeReviewsSocket(pushed));
            }

            @Override
            public java.util.Iterator<WebSocketConnection> iterator() {
                return stream().iterator();
            }
        };

        withFakeSocket.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.MINOR, "shadows the field");

        assertTrue(pushed.stream().anyMatch(json -> json.contains(reviewId)),
                "a client connected to the reviews socket must be pushed the finding, not just have "
                        + "it written to the database");
    }

    /**
     * Enough of {@link WebSocketConnection} to exercise {@code ReviewProjection#push}'s path filter
     * and JSON send — every other member is unreachable from that method and throws if called.
     */
    private static final class FakeReviewsSocket implements WebSocketConnection {
        private final List<String> sink;

        FakeReviewsSocket(List<String> sink) {
            this.sink = sink;
        }

        @Override
        public HandshakeRequest handshakeRequest() {
            return new HandshakeRequest() {
                @Override
                public String path() {
                    return "/api/ws/reviews";
                }

                @Override
                public String header(String name) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public List<String> headers(String name) {
                    throw new UnsupportedOperationException();
                }

                @Override
                public Map<String, List<String>> headers() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public String scheme() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public String host() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public int port() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public String query() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public String localAddress() {
                    throw new UnsupportedOperationException();
                }

                @Override
                public String remoteAddress() {
                    throw new UnsupportedOperationException();
                }
            };
        }

        @Override
        public Uni<Void> sendText(String text) {
            sink.add(text);
            return Uni.createFrom().nullItem();
        }

        @Override
        public String id() {
            throw new UnsupportedOperationException();
        }

        @Override
        public String pathParam(String name) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isSecure() {
            throw new UnsupportedOperationException();
        }

        @Override
        public SSLSession sslSession() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isClosed() {
            throw new UnsupportedOperationException();
        }

        @Override
        public CloseReason closeReason() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Uni<Void> close(CloseReason reason) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String subprotocol() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Instant creationTime() {
            throw new UnsupportedOperationException();
        }

        @Override
        public UserData userData() {
            throw new UnsupportedOperationException();
        }

        @Override
        public <M> Uni<Void> sendText(M message) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Uni<Void> sendBinary(Buffer buffer) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Uni<Void> sendPing(Buffer data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Uni<Void> sendPong(Buffer data) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String endpointId() {
            throw new UnsupportedOperationException();
        }

        @Override
        public BroadcastSender broadcast() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Set<WebSocketConnection> getOpenConnections() {
            throw new UnsupportedOperationException();
        }
    }

    // ---- the findings card's union of findings_json + open_findings_json --------------------

    /**
     * The defect this union closes: {@code loadDetail} built its findings list from
     * {@code findings_json} alone, whose one writer ({@code recordOutcome}) serializes a 4-arg
     * {@code FindingView} and so can never produce an origin at all. A {@code /finding} therefore
     * reached the dashboard nowhere — not the card, not the counts — while every other consumer of
     * the baseline had it.
     */
    @Test
    void aConversationFindingIsVisibleOnTheFindingsCard() {
        long pr = ReviewFixtures.newPr();
        String reviewId = registerReviewWithOpenFindings(pr, "src/Bar.java:10", "warning");

        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.MINOR, "shadows the field");

        ReviewDetail detail = projection.loadDetail(ReviewFixtures.WS, ReviewFixtures.REPO, pr).orElseThrow();
        ReviewDetail.FindingView filed = detail.findingsList().stream()
                .filter(f -> "src/Foo.java:44".equals(f.loc())).findFirst().orElseThrow(
                        () -> new AssertionError("the findings card never showed the filed finding"));
        assertEquals("conversation", filed.origin());
        assertEquals("t-900", filed.threadRef(),
                "the card must keep the thread the human filed it in — that thread is not a "
                        + "review_thread finding row, so a loc lookup would null it and unhook the "
                        + "conversation from the row");
    }

    /**
     * The counts move on the same union basis as the card. Both figures are asserted from ONE
     * severity: a blocker filed from a discussion is the case where a card that shows it and a
     * badge that says "passed" contradict each other on the same page.
     */
    @Test
    void aConversationFindingCountsTowardTheOpenAndBlockerCounts() {
        long pr = ReviewFixtures.newPr();
        String reviewId = registerReviewWithOpenFindings(pr, "src/Bar.java:10", "warning");
        ReviewDetail before = projection.loadDetail(ReviewFixtures.WS, ReviewFixtures.REPO, pr).orElseThrow();
        assertEquals(1, before.openFindings(), "setup check: only the seeded review finding is open");
        assertEquals(0, before.openBlockers(), "setup check: the seeded finding is not a blocker");

        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.BLOCKER, "TEST-BLOCKER filed from a discussion");

        ReviewDetail after = projection.loadDetail(ReviewFixtures.WS, ReviewFixtures.REPO, pr).orElseThrow();
        assertEquals(2, after.openFindings(), "an open finding a human filed is still an open finding");
        assertEquals(1, after.openBlockers(), "a blocker a human filed still blocks");
    }

    /**
     * {@code hasOpenFindingAt} used to read {@code findings_json} and {@code reconciliation_json}
     * only — never {@code open_findings_json}, the ONLY column {@link ReviewProjection#addConversationFinding}
     * ever writes to. So a human who filed a finding in a thread they started got silence on every
     * later reply at that very line: {@code ConversationSaga}'s "a thread on a flagged line still
     * engages" check reads this method to decide.
     */
    @Test
    void hasOpenFindingAtSeesAFindingFiledFromAConversation() {
        String reviewId = registerReviewWithOpenFindings("src/Bar.java:10", "warning");
        assertFalse(projection.hasOpenFindingAt(reviewId, "src/Foo.java:44"),
                "setup check: nothing is open at this loc yet");

        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.MINOR, "shadows the field");

        assertTrue(projection.hasOpenFindingAt(reviewId, "src/Foo.java:44"),
                "a finding a human filed from a discussion must still read as open");
    }

    /**
     * Round N+1: the finding is no longer a fresh baseline entry but a reconciliation verdict, and
     * {@code ReconciliationView} had no origin field at all — so the card's provenance badge was
     * unreachable from the round after the one that filed it, which is the longer-lived half of the
     * defect.
     */
    @Test
    void aReconciledConversationFindingKeepsItsOriginOnItsReconciliationRow() {
        long pr = ReviewFixtures.newPr();
        String reviewId = registerReviewWithOpenFindings(pr, "src/Bar.java:10", "warning");
        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.MINOR, "shadows the field");
        List<PriorFinding> priorFindings = projection.priorRunFor(reviewId).orElseThrow().findings();

        List<FindingVerdict> verdicts = List.of(new FindingVerdict("t-900", "src/Foo.java", 44,
                FindingVerdict.Status.STILL_OPEN, "still there after the follow-up commit"));
        projection.recordReconciliation(reviewId, verdicts, priorFindings);

        ReviewDetail detail = projection.loadDetail(ReviewFixtures.WS, ReviewFixtures.REPO, pr).orElseThrow();
        ReviewDetail.ReconciliationView row = detail.reconciliation().stream()
                .filter(r -> "src/Foo.java:44".equals(r.loc())).findFirst().orElseThrow();
        assertEquals("conversation", row.origin(),
                "a verdict on a human-filed finding must still say who filed it");
        // And the union yields to it rather than shadowing it. The baseline copy still exists, but
        // rendering it would put the finding back under "new" a round after it was filed and lose the
        // verdict's own note — one concern on one row, and the richer row wins.
        assertTrue(detail.findingsList().stream().noneMatch(f -> "src/Foo.java:44".equals(f.loc())),
                "an open verdict owns the row; the baseline copy must not be listed beside it");
        assertEquals(2, detail.openFindings(),
                "two concerns are open — the seeded review finding and the filed one — so the filed "
                        + "one is counted once across its baseline copy and its verdict, not twice");
    }

    /**
     * Defense on read, the same posture {@code priorRunFor} takes for the posted snapshot: a stored
     * baseline can hold two entries at one anchor even though {@code dedupeByAnchor} makes that
     * impossible on today's write path — a row written by an older build is exactly that. One anchor
     * is one concern on the card and one entry in the counts, whatever the column happens to hold.
     */
    @Test
    void aBaselineHoldingTwoEntriesAtOneAnchorStillShowsOneRow() throws SQLException {
        long pr = ReviewFixtures.newPr();
        String reviewId = registerReviewWithOpenFindings(pr, "src/Bar.java:10", "warning");
        writeRawOpenFindings(reviewId, """
                [{"sev":"suggestion","loc":"src/Foo.java:44","msg":"shadows the field",\
                "threadRef":"t-900","origin":"conversation"},\
                {"sev":"critical","loc":"src/Foo.java:44","msg":"and it leaks",\
                "threadRef":"t-901","origin":"conversation"}]""");

        ReviewDetail detail = projection.loadDetail(ReviewFixtures.WS, ReviewFixtures.REPO, pr).orElseThrow();
        assertEquals(1, detail.findingsList().stream()
                        .filter(f -> "src/Foo.java:44".equals(f.loc())).count(),
                "one anchor is one row, even when the stored column disagrees");
        assertEquals(2, detail.openFindings(),
                "and one entry in the counts — the seeded finding plus this single anchor");
    }

    /**
     * One discussion can raise more than one finding. {@code /finding} anchors to whatever line the
     * command names, and every finding filed in a thread carries that thread's root ref, so two of
     * them share a {@code threadRef} while being genuinely separate concerns at separate anchors.
     * Anchor is the identity on this card, exactly as {@code dedupeByAnchor} makes it on the write
     * side — deduping on thread instead would silently swallow the second.
     */
    @Test
    void twoFindingsFiledFromOneThreadBothAppear() {
        long pr = ReviewFixtures.newPr();
        String reviewId = registerReviewWithOpenFindings(pr, "src/Bar.java:10", "warning");

        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.MINOR, "shadows the field");
        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 88,
                Severity.BLOCKER, "TEST-BLOCKER and this one leaks");

        ReviewDetail detail = projection.loadDetail(ReviewFixtures.WS, ReviewFixtures.REPO, pr).orElseThrow();
        assertTrue(detail.findingsList().stream().anyMatch(f -> "src/Foo.java:44".equals(f.loc())),
                "the first finding filed from the thread must be shown");
        assertTrue(detail.findingsList().stream().anyMatch(f -> "src/Foo.java:88".equals(f.loc())),
                "and so must the second — one thread can raise several distinct concerns");
        assertEquals(3, detail.openFindings(), "all three concerns are open");
        assertEquals(1, detail.openBlockers(), "including the blocker the second one raised");
    }

    /**
     * The mirror of the case below: the verdict and the baseline copy can disagree about the ANCHOR
     * while agreeing about the thread, which is the other reason an open verdict claims both.
     *
     * <p>A verdict's path/line is remapped through whatever rename the worker followed (ADR-019),
     * while the baseline copy keeps the anchor the human filed against until the round's
     * {@code recordOpenFindings} rewrites it. Matching on anchor alone would list the same concern
     * twice at two different locations — the most confusing shape this card can take.
     */
    @Test
    void aVerdictThatFollowedARenameStillClaimsItsThread() {
        long pr = ReviewFixtures.newPr();
        String reviewId = registerReviewWithOpenFindings(pr, "src/Bar.java:10", "warning");
        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.MINOR, "shadows the field");
        List<PriorFinding> priorFindings = projection.priorRunFor(reviewId).orElseThrow().findings();

        // The follow-up renamed the file, so the verdict names src/Foo2.java:50 while the baseline
        // copy still names src/Foo.java:44 — the same thread, two anchors.
        projection.recordReconciliation(reviewId, List.of(new FindingVerdict("t-900",
                "src/Foo2.java", 50, FindingVerdict.Status.STILL_OPEN, "still there")), priorFindings);

        ReviewDetail detail = projection.loadDetail(ReviewFixtures.WS, ReviewFixtures.REPO, pr).orElseThrow();
        assertTrue(detail.findingsList().stream().noneMatch(f -> "src/Foo.java:44".equals(f.loc())),
                "the concern moved with the rename; its pre-rename anchor must not be listed beside it");
        assertEquals(2, detail.openFindings(),
                "the seeded finding and the filed one — the filed one counted once, not once per anchor");
    }

    /**
     * The verdict and the baseline copy can disagree about the THREAD while agreeing about the
     * anchor, which is why an open verdict claims both.
     *
     * <p>{@code toPriorFinding} substitutes the newest {@code review_thread} row for a loc, so once a
     * later post lands on the filed finding's line the verdict carries that newer thread while
     * {@code open_findings_json} still carries the one the human filed in. Matching on thread alone
     * would then see two unrelated rows and put the same concern on the card twice — once as a verdict
     * and once as a fresh finding.
     */
    @Test
    void aVerdictOnASupersededThreadStillClaimsItsAnchor() {
        long pr = ReviewFixtures.newPr();
        String reviewId = registerReviewWithOpenFindings(pr, "src/Bar.java:10", "warning");
        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.MINOR, "shadows the field");
        // A later post at the same loc — "newest row wins" now points away from the human's t-900.
        threads.markFindingThread(reviewId, new ThreadRef("newer-thread"), "src/Foo.java", 44);

        List<PriorFinding> priorFindings = projection.priorRunFor(reviewId).orElseThrow().findings();
        assertTrue(priorFindings.stream().anyMatch(pf -> "newer-thread".equals(pf.threadRef())),
                "setup check: the newer thread must have superseded the filed finding's own");
        projection.recordReconciliation(reviewId, List.of(new FindingVerdict("newer-thread",
                "src/Foo.java", 44, FindingVerdict.Status.STILL_OPEN, "still there")), priorFindings);

        ReviewDetail detail = projection.loadDetail(ReviewFixtures.WS, ReviewFixtures.REPO, pr).orElseThrow();
        assertTrue(detail.findingsList().stream().noneMatch(f -> "src/Foo.java:44".equals(f.loc())),
                "the verdict owns this anchor even though its thread is not the one the human filed in");
        assertEquals(2, detail.openFindings(),
                "the seeded finding and the filed one — the filed one counted once, not once per thread");
    }

    /**
     * A closed verdict claims nothing. Its anchor is vacated, so a human filing a fresh
     * {@code /finding} on that same line is raising a NEW concern — and the resolved row still sitting
     * in the reconciliation history must not swallow it, which is the failure mode a plain
     * "reconciliation already names this loc" test would have.
     */
    @Test
    void aFindingFiledOnAnAnchorAResolvedVerdictVacatedIsStillShown() {
        long pr = ReviewFixtures.newPr();
        String reviewId = registerReviewWithOpenFindings(pr, "src/Foo.java:44", "warning");
        List<PriorFinding> priorFindings = projection.priorRunFor(reviewId).orElseThrow().findings();

        // The seeded finding is judged fixed, leaving a RESOLVED row at src/Foo.java:44.
        List<FindingVerdict> verdicts = List.of(new FindingVerdict(null, "src/Foo.java", 44,
                FindingVerdict.Status.RESOLVED, "fixed"));
        projection.recordReconciliation(reviewId, verdicts, priorFindings);
        ReviewResult quiet = new ReviewResult(List.of(), "TEST-SUMMARY-2", ModelUsage.of("TEST-MODEL", 1, 1));
        projection.recordOutcome(reviewId, quiet, ReviewProjection.STAGE_COMMENTS);
        projection.recordOpenFindings(reviewId, quiet, verdicts, priorFindings);

        // A person then files a new concern on the very line that was just closed out.
        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.BLOCKER, "TEST-BLOCKER on the same line, a different problem");

        ReviewDetail detail = projection.loadDetail(ReviewFixtures.WS, ReviewFixtures.REPO, pr).orElseThrow();
        assertTrue(detail.findingsList().stream()
                        .anyMatch(f -> "src/Foo.java:44".equals(f.loc())
                                && "conversation".equals(f.origin())),
                "a resolved verdict must not hide a new finding filed on the anchor it vacated");
        assertEquals(1, detail.openBlockers(), "and the new blocker must be counted");
    }

    /**
     * The union takes the conversation-origin entries of {@code open_findings_json} and nothing else,
     * which is what keeps it a union rather than a repoint.
     *
     * <p>That column is the NEXT round's baseline: it also holds prior findings carried forward
     * because they are still open. Those belong to the reconciliation card, which says what happened
     * to them; re-listing them here would make the findings card silently claim this run raised them,
     * and would count them a second time beside their own verdict. Only the human-filed entries have
     * no other home.
     */
    @Test
    void aCarriedPriorFindingInTheBaselineStaysOffTheFindingsCard() {
        long pr = ReviewFixtures.newPr();
        String reviewId = registerReviewWithOpenFindings(pr, "src/Bar.java:10", "warning");

        // A later round reports nothing, so findings_json no longer names Bar.java:10 — but the
        // baseline still carries it, because nothing has judged it resolved.
        ReviewResult quiet = new ReviewResult(List.of(), "TEST-SUMMARY-2", ModelUsage.of("TEST-MODEL", 1, 1));
        projection.recordOutcome(reviewId, quiet, ReviewProjection.STAGE_COMMENTS);
        assertTrue(projection.openFindingsFor(reviewId).stream()
                        .anyMatch(f -> "src/Bar.java:10".equals(f.loc())),
                "setup check: the baseline must still carry the review-derived finding");

        ReviewDetail detail = projection.loadDetail(ReviewFixtures.WS, ReviewFixtures.REPO, pr).orElseThrow();
        assertTrue(detail.findingsList().stream().noneMatch(f -> "src/Bar.java:10".equals(f.loc())),
                "a carried review-derived finding belongs to the reconciliation card, not this one");
        assertEquals(0, detail.openFindings(),
                "and it must not be counted from the baseline either — its verdict is what counts it");
    }

    /**
     * The union's own hazard, and the reason it dedupes by anchor rather than concatenating.
     *
     * <p>{@code recordOutcome} and {@code recordOpenFindings} are two separate writes, and the first
     * of them broadcasts — so there is a real window in which {@code findings_json} already names the
     * anchor this round while {@code open_findings_json} still holds the human's own entry for it.
     * A page loaded in that window must show one concern, not the same line twice with two different
     * messages, and must count it once.
     */
    @Test
    void anAnchorNamedByBothColumnsAppearsOnceOnTheCardAndCountsOnce() {
        long pr = ReviewFixtures.newPr();
        String reviewId = registerReviewWithOpenFindings(pr, "src/Bar.java:10", "warning");
        projection.addConversationFinding(reviewId, "t-900", "src/Foo.java", 44,
                Severity.MINOR, "shadows the field");

        // The next round's model reports the very same anchor. recordOutcome lands first; nothing has
        // merged the two columns yet.
        ReviewResult next = new ReviewResult(
                List.of(new Finding("src/Foo.java", new LineRange(44, 44), Severity.MAJOR,
                        "the model found it too", null)),
                "TEST-SUMMARY-2", ModelUsage.of("TEST-MODEL", 1, 1));
        projection.recordOutcome(reviewId, next, ReviewProjection.STAGE_COMMENTS);

        ReviewDetail detail = projection.loadDetail(ReviewFixtures.WS, ReviewFixtures.REPO, pr).orElseThrow();
        assertEquals(1, detail.findingsList().stream()
                        .filter(f -> "src/Foo.java:44".equals(f.loc())).count(),
                "one anchor is one tracked concern on the card, whichever column named it");
        assertEquals(1, detail.openFindings(),
                "and it is counted once — a union that double-counts is worse than one that omits");
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
        return registerReviewWithOpenFindings(ReviewFixtures.newPr(), loc, sevSlug);
    }

    /** The same seed for a test that also needs the PR number: {@link ReviewProjection#loadDetail}
     *  is addressed by workspace/slug/pr, not by reviewId. */
    private String registerReviewWithOpenFindings(long pr, String loc, String sevSlug) {
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
