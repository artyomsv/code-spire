package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.AuthorReplied;
import dev.codespire.contract.event.IntegrationEvent.CloseReason;
import dev.codespire.contract.event.IntegrationEvent.ManualCommandReceived;
import dev.codespire.contract.event.IntegrationEvent.PrAction;
import dev.codespire.contract.event.IntegrationEvent.PullRequestClosed;
import dev.codespire.contract.event.IntegrationEvent.PullRequestEventReceived;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.ThreadLocation;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.policy.ReviewPolicy;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.provider.ReviewProviderResolver;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.provider.ProviderRole;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ArchiveOutcome;
import dev.codespire.orchestrator.readmodel.ReviewFixtures;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.readmodel.ReviewThreadView;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.LongFunction;
import java.util.stream.Stream;

import static dev.codespire.orchestrator.readmodel.ReviewFixtures.REPO;
import static dev.codespire.orchestrator.readmodel.ReviewFixtures.REPO_REF;
import static dev.codespire.orchestrator.readmodel.ReviewFixtures.WS;
import static dev.codespire.orchestrator.readmodel.ReviewFixtures.reviewIdFor;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * An archived PR is retired: the four inbound events that could resurrect it stop at the gate, and the
 * three that carry a human's question answer once instead of going silent.
 *
 * <p>{@code @QuarkusTest} on purpose. The frozen assertion is "this row is unchanged", read back from
 * the real {@code review_status} — with an in-memory fake it would be an assertion about the fake. It
 * is deliberately NOT phrased as "no new review row was created": that cannot fail, because the table's
 * primary key forbids a second row for one PR whatever the gate does.
 *
 * <p>Every fake here is wired so that a MISSING gate produces a visible difference — the decider claims
 * a run, the provider registry resolves, and the conversation saga plans a real answer. A fake that
 * declined would let the ungated path skip early and pass this suite vacuously.
 */
@QuarkusTest
class ArchivedEventGateTest {

    private static final String BOT_ACCOUNT_ID = "TEST-BOT-ACCOUNT";
    private static final Author HUMAN = Author.of("TEST-HUMAN-ID", "TEST-HUMAN", "Test Human");
    private static final Author BOT = Author.of(BOT_ACCOUNT_ID, "TEST-BOT", "Test Bot");
    private static final ThreadLocation LOCATION = new ThreadLocation("TEST/Example.java", 12);

    @Inject
    ReviewProjection projection;

    @Inject
    ReviewThreadView threads;

    @Inject
    DataSource dataSource;

    private final List<ActionCommand> emitted = new ArrayList<>();
    private final List<RecordCommand> recorded = new ArrayList<>();
    private final List<String> followUpsPlanned = new ArrayList<>();
    private final List<String> rerunsRequested = new ArrayList<>();

    static Stream<Arguments> conversationalEvents() {
        return Stream.of(
                Arguments.of("a reply in a thread",
                        (LongFunction<IntegrationEvent>) ArchivedEventGateTest::reply),
                Arguments.of("a /review command",
                        (LongFunction<IntegrationEvent>) ArchivedEventGateTest::reviewCommand),
                Arguments.of("a push to the pull request",
                        (LongFunction<IntegrationEvent>) ArchivedEventGateTest::pushed));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("conversationalEvents")
    void aConversationalEventLeavesAnArchivedReviewUnchangedAndNotifiesOnce(
            String label, LongFunction<IntegrationEvent> eventFor) {
        long pr = seedArchived();
        String before = snapshotOf(pr);

        sagaAnsweringEveryone().on(eventFor.apply(pr));

        assertEquals(before, snapshotOf(pr), label + " must leave an archived review frozen");
        assertEquals(1, emitted.size(), () -> "expected exactly the notice, got " + emitted);
        assertInstanceOf(ActionCommand.NotifyArchived.class, emitted.getFirst());
        assertTrue(recorded.isEmpty(), "an archived review's aggregate is never advanced");
    }

    /**
     * The control the three gated cases need: with the same wiring, a LIVE review still runs its normal
     * handling. Without this, every "nothing happened" assertion above would also pass if the saga had
     * simply stopped working.
     */
    @Test
    void aLiveReviewStillRunsItsNormalHandling() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);

        sagaAnsweringEveryone().on(reviewCommand(pr));

        assertEquals(List.of(WS + "/" + REPO + "#" + pr), rerunsRequested);
        assertTrue(emitted.isEmpty(), "a live review's /review posts no archived notice");
    }

    /**
     * A close is the event that writes {@code pr_state}, so gating it is what makes the frozen badge
     * true — and it must NOT spend the notice: the notice fires once ever, and a close is nobody
     * asking a question, so spending it there leaves the person who later asks a real one with silence.
     */
    @Test
    void closingAnArchivedPrIsGatedButSpendsNoNotice() {
        long pr = seedArchived();
        // The positive half first: a live PR's close really does move the badge, so the frozen
        // assertion below is about the gate and not about a close that never worked.
        long live = ReviewFixtures.newPr();
        ReviewFixtures.seedCompletedReviewWithCharges(projection, live);
        sagaAnsweringEveryone().on(closed(live));
        assertEquals("MERGED", prStateOf(live));

        sagaAnsweringEveryone().on(closed(pr));

        assertEquals("OPEN", prStateOf(pr), "an archived review's PR-state badge is frozen at archival");
        assertTrue(emitted.isEmpty(), "a close must leave the once-ever notice unspent");
    }

    /** The bot's own posted notice echoes back as a reply; answering it would emit a command forever. */
    @Test
    void theBotsOwnNoticeDoesNotRetriggerTheGate() {
        long pr = seedArchived();

        sagaAnsweringEveryone().on(replyBy(pr, BOT));

        assertTrue(emitted.isEmpty(), "the bot's own comment is not a human asking a question");
    }

    /**
     * {@code /review} was gated on the per-provider allowlist precisely so an unlisted author cannot
     * make the bot act. A notice that answered any commenter would partly reverse that.
     */
    @Test
    void anUnlistedAuthorGetsNoNoticeWhileAnAllowlistedOneStillDoes() {
        long pr = seedArchived();
        IntegrationSaga saga = sagaFor(provider(List.of(HUMAN.username())));

        saga.on(replyBy(pr, HUMAN));
        assertEquals(1, emitted.size(), "an allowlisted author is answered");

        saga.on(replyBy(pr, Author.of("TEST-MALLORY-ID", "TEST-MALLORY", "Test Mallory")));
        assertEquals(1, emitted.size(), "an unlisted author adds nothing");
    }

    /**
     * A command with no credential reaches the worker's stub-sink fallback, which would consume the
     * once-ever claim while posting nothing real — the worst outcome, since the notice is then
     * permanently spent and invisible.
     */
    @Test
    void anUnresolvableProviderEmitsNothingRatherThanACredentiallessNotice() {
        long pr = seedArchived();

        sagaFor(Optional.empty()).on(reply(pr));

        assertTrue(emitted.isEmpty(), "no provider, no credential, no notice");
    }

    /** The {@code AuthorReplied} branch writes the thread's location on its way past — before the gate,
     *  that write lands on an archived review. */
    @Test
    void aReplyToAnArchivedReviewNeverRecordsItsThreadLocation() throws SQLException {
        long live = ReviewFixtures.newPr();
        ReviewFixtures.seedCompletedReviewWithCharges(projection, live);
        sagaAnsweringEveryone().on(reply(live));
        assertTrue(threadRowExists(reviewIdFor(live), threadRefOf(live)),
                "a live reply records where its thread sits");

        long pr = seedArchived();
        sagaAnsweringEveryone().on(reply(pr));

        assertFalse(threadRowExists(reviewIdFor(pr), threadRefOf(pr)),
                "the gate must run before markThreadLocation");
        assertEquals(List.of(reviewIdFor(live)), followUpsPlanned,
                "and before the conversation policy is consulted — only the live reply reached it");
    }

    // ---- fixtures ----------------------------------------------------------

    private long seedArchived() {
        long pr = ReviewFixtures.newPr();
        ReviewFixtures.seedCompletedReviewWithCharges(projection, pr);
        assertEquals(ArchiveOutcome.ARCHIVED, projection.archiveReview(WS, REPO, pr));
        return pr;
    }

    /** The whole detail row as text: status, stage, PR state, cost lines and the review's own history. */
    private String snapshotOf(long pr) {
        return projection.loadDetail(WS, REPO, pr).orElseThrow().toString();
    }

    private String prStateOf(long pr) {
        return projection.loadDetail(WS, REPO, pr).orElseThrow().prState();
    }

    private boolean threadRowExists(String reviewId, String threadRef) throws SQLException {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT 1 FROM review_thread WHERE review_id = ? AND thread_ref = ?")) {
            ps.setString(1, reviewId);
            ps.setString(2, threadRef);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static String threadRefOf(long pr) {
        return "TEST-THREAD-" + pr;
    }

    private static IntegrationEvent reply(long pr) {
        return replyBy(pr, HUMAN);
    }

    private static AuthorReplied replyBy(long pr, Author author) {
        return new AuthorReplied(REPO_REF, pr, reviewIdFor(pr), new ThreadRef(threadRefOf(pr)),
                "TEST-COMMENT-" + pr, "TEST is this still being reviewed?", author, false, List.of(),
                LOCATION);
    }

    private static IntegrationEvent reviewCommand(long pr) {
        return new ManualCommandReceived(REPO_REF, pr, "review", "", HUMAN);
    }

    private static IntegrationEvent pushed(long pr) {
        return new PullRequestEventReceived(REPO_REF, pr, PrAction.UPDATED, "TEST-TITLE", "TEST-DESC",
                "TEST-SOURCE", "TEST-TARGET", "TESTSHA" + pr + "B", HUMAN,
                "http://example.invalid/pr/" + pr, "github");
    }

    private static IntegrationEvent closed(long pr) {
        return new PullRequestClosed(REPO_REF, pr, CloseReason.MERGED);
    }

    private static Optional<ScmProvider> provider(List<String> authors) {
        return Optional.of(new ScmProvider(UUID.randomUUID(), "TEST-PROVIDER", "github",
                "https://example.invalid", WS, "bearer", null, "TEST-SECRET", BOT_ACCOUNT_ID, true,
                authors, null, null, ProviderRole.REVIEWER));
    }

    private IntegrationSaga sagaAnsweringEveryone() {
        return sagaFor(provider(List.of()));
    }

    /** Real read model, faked edges — so "the row is unchanged" is a real row and nothing is posted. */
    private IntegrationSaga sagaFor(Optional<ScmProvider> provider) {
        IntegrationSaga saga = new IntegrationSaga();
        saga.projection = projection;
        saga.threads = threads;
        saga.policy = new ReviewPolicy() {
            @Override
            public boolean observeOnly() {
                return false;
            }
        };
        saga.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
            }
        };
        saga.commands = new CommandsEmitter() {
            @Override
            public void emit(ActionCommand command) {
                emitted.add(command);
            }
        };
        // Claims a run, so an ungated PR event would really overwrite the archived row's status.
        saga.lifecycle = new ReviewLifecycleService() {
            @Override
            public List<DomainEvent> handle(String reviewId, RecordCommand command) {
                recorded.add(command);
                return List.of(new DomainEvent.ReviewRequested("TESTSHA", "UPDATED"));
            }
        };
        saga.providers = new ProviderRegistry() {
            @Override
            public Optional<ScmProvider> resolve(String type, String workspace) {
                return provider;
            }

            @Override
            public Optional<ScmProvider> resolveByWorkspace(String workspace) {
                return provider;
            }
        };
        saga.reviewProviders = new ReviewProviderResolver() {
            @Override
            public Optional<ScmProvider> resolveForReview(String reviewId) {
                return provider;
            }
        };
        saga.workerCredentials = new WorkerCredentials() {
            @Override
            public String pack(ScmProvider p) {
                return "TEST-PACKED-CREDENTIAL";
            }
        };
        // Plans a real answer, so an ungated reply would emit an AnswerFollowUp and flag "answering".
        saga.conversation = new ConversationSaga() {
            @Override
            public Optional<ActionCommand> planFollowUp(AuthorReplied e) {
                followUpsPlanned.add(e.reviewId());
                return Optional.of(new ActionCommand.AnswerFollowUp(e.reviewId(), e.repo(), e.prId(),
                        e.threadRef(), e.commentId(), e.text(), "TEST-SCM-CREDENTIAL",
                        "TEST-LLM-CREDENTIAL", false, 1, 100L, 2.0));
            }
        };
        saga.rerunService = new ReviewRerunService() {
            @Override
            public boolean rerun(String workspace, String slug, long pr) {
                rerunsRequested.add(workspace + "/" + slug + "#" + pr);
                return true;
            }
        };
        return saga;
    }
}
