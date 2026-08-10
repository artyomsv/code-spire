package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.event.IntegrationEvent.AuthorReplied;
import dev.codespire.contract.review.ConversationLevel;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.caps.CapRefusal;
import dev.codespire.orchestrator.caps.SpendGate;
import dev.codespire.orchestrator.llm.DefaultLlm;
import dev.codespire.orchestrator.llm.WorkerLlmCredentials;
import dev.codespire.orchestrator.prompt.WorkerPromptTemplates;
import dev.codespire.orchestrator.provider.ConversationLevels;
import dev.codespire.orchestrator.provider.ReviewProviderResolver;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.readmodel.ReviewThreadView;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The spend cap on the conversation path — the one genuinely unbounded spend path in the system.
 *
 * <p>Threads cost nothing to open, the turn cap is per-<i>thread</i>, and an @-mention removes it
 * entirely ({@code CallRefs}: "the default turn cap is 4, and an @-mention removes the cap entirely, so
 * the loss was unbounded"). Until this gate the only guard here was {@code isSpendable}, which answers
 * "can this model be priced at all", not "may we spend more".
 *
 * <p>Hand-wired fakes, no CDI — the posture {@link ConversationSagaTest} establishes for this saga. The
 * <i>verdict</i> (which axis trips, at what threshold, over what window) is not re-tested here: both
 * sagas call one {@link SpendGate}, so they reach the same verdict from the same inputs by construction,
 * and {@code SpendCapGateTest} exercises that verdict against the real ledger and settings store.
 */
class ConversationSpendCapTest {

    private static final RepoRef REPO = new RepoRef("TEST-WS", "TEST-REPO");
    private static final String REVIEW_ID = "review::TEST-WS/TEST-REPO#412";

    /** The cap {@link #fixedLevel} configures, so "this many prior turns" means "past the turn cap". */
    private static final int TURN_CAP = 4;

    private final List<String> timelineTypes = new ArrayList<>();
    private final List<String> timelineDetails = new ArrayList<>();
    private final List<String> appendedTypes = new ArrayList<>();
    private final List<String> appendedThreadRefs = new ArrayList<>();

    @Test
    void aFollowUpIsRefusedWhenTheCapIsReached() {
        ConversationSaga saga = sagaWith(0, refusingGate());

        assertTrue(saga.planFollowUp(inlineReply(List.of())).isEmpty(),
                "no paid command may be planned once the deployment is over its cap");
        assertTrue(timelineTypes.contains("refused:AnswerFollowUp"),
                "a refusal nobody can see is the failure this project has already paid for twice: "
                        + timelineTypes);
        assertTrue(timelineDetails.stream().anyMatch(detail -> detail.contains("call cap")),
                "and it must name the cap that fired, not merely report a skip: " + timelineDetails);
    }

    /**
     * The timeline is not a record. It is an in-memory list capped at 500 entries DEPLOYMENT-WIDE and
     * lost on restart, and this suite's other assertions pin it as the contract — so the gap could not
     * be caught by test drift either. Without a persisted event the only durable trace of an unanswered
     * reply is the deployment-wide {@code CAP_REACHED} row, which never says which thread went quiet.
     *
     * <p>Filed under the conversation root, so it sits with the thread it belongs to rather than
     * spilling into General discussion.
     */
    @Test
    void aRefusedFollowUpLeavesADurableRecordOnTheReview() {
        ConversationSaga saga = sagaWith(0, refusingGate());

        assertTrue(saga.planFollowUp(inlineReply(List.of())).isEmpty());

        assertTrue(appendedTypes.contains("FollowUpRefused"),
                "the timeline is ephemeral; the review's own event log is not: " + appendedTypes);
        assertEquals(List.of("finding-1"), appendedThreadRefs,
                "and it belongs to the thread that went unanswered");
    }

    /**
     * The test this whole task turns on. {@link #TURN_CAP} turns are already spent, so this reply can
     * only reach the paid path through the @-mention override that
     * {@code ConversationSagaTest#aMentionPastTheCapPlansARealAnswerNotTheNotice} pins as deliberate.
     * That override must keep bypassing the TURN cap and must not bypass the SPEND cap — a gate placed
     * anywhere the mention check short-circuits leaves this reply answered and the unbounded case
     * unbounded.
     */
    @Test
    void anAtMentionDoesNotBypassTheSpendCap() {
        ConversationSaga saga = sagaWith(TURN_CAP, refusingGate());

        assertTrue(saga.planFollowUp(inlineReply(List.of("code-spire"))).isEmpty(),
                "an @-mention removes the turn cap by design; it must not also remove the spend cap");
        assertTrue(timelineTypes.contains("refused:AnswerFollowUp"),
                "and the mention path must refuse out loud, not simply return nothing: " + timelineTypes);
    }

    /**
     * The hand-off notice is fixed text with no LLM credential, so a spend cap has nothing to refuse in
     * it. Silencing it would restore the exact failure it was built to fix: the bot going quiet mid-thread,
     * indistinguishable from a lost webhook. This also fails if the gate is hoisted above the turn-cap
     * decision, where it would swallow the free command along with the paid one.
     */
    @Test
    void theFreeTurnCapNoticeIsStillPlannedWhileTheCapIsReached() {
        ConversationSaga saga = sagaWith(TURN_CAP, refusingGate());

        ActionCommand.NotifyTurnCap notice = assertInstanceOf(ActionCommand.NotifyTurnCap.class,
                saga.planFollowUp(inlineReply(List.of())).orElseThrow(),
                "a capped thread still hands off — the spend cap bounds paid calls, not free ones");
        assertTrue(notice.llmCredential() == null, "which is only true because the notice buys nothing");
    }

    /**
     * A refused reply must NOT move the review. The review may have completed, and refusing to answer one
     * reply is not a retraction of that outcome — this is where the conversation gate deliberately parts
     * company with the two review gates, whose refusal <i>is</i> the review's outcome.
     */
    @Test
    void aRefusedFollowUpLeavesTheReviewAlone() {
        ConversationSaga saga = sagaWith(0, refusingGate());
        saga.projection = new ReviewProjection() {
            @Override
            public Optional<PriorRun> priorRunFor(String reviewId) {
                return Optional.empty();
            }

            @Override
            public void updateStatus(String reviewId, String status, int stage) {
                throw new AssertionError("a completed review must not be dragged back to '" + status
                        + "' because one reply went unanswered");
            }

            @Override
            public void setNote(String reviewId, String note) {
                throw new AssertionError("the note is the REVIEW's verdict, and CapRefusal.note() opens "
                        + "\"Not reviewed\" — false on a review that was reviewed: " + note);
            }

            // Appending to the review's event log is exactly what the refusal SHOULD do — it records
            // the fact without restating the review's outcome, which is what the two throws above forbid.
            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail,
                                    String threadRef) {
            }
        };

        assertTrue(saga.planFollowUp(inlineReply(List.of())).isEmpty());
    }

    /** An unset cap must be a complete no-op here too, or every deployment changes behaviour on upgrade. */
    @Test
    void anAllowedDecisionAnswersExactlyAsBefore() {
        ConversationSaga saga = sagaWith(0, allowingGate());

        assertInstanceOf(ActionCommand.AnswerFollowUp.class,
                saga.planFollowUp(inlineReply(List.of())).orElseThrow());
        assertFalse(timelineTypes.contains("refused:AnswerFollowUp"));
    }

    // --- fakes ---

    /** Refuses on the call axis, which is the axis that bounds an UNMETERED deployment (ADR-023). */
    private static SpendGate refusingGate() {
        return new SpendGate() {
            @Override
            public Decision decide() {
                return Decision.of(CapRefusal.callCapReached(5, 1));
            }
        };
    }

    private static SpendGate allowingGate() {
        return new SpendGate() {
            @Override
            public Decision decide() {
                return Decision.of(CapRefusal.allow());
            }
        };
    }

    private static AuthorReplied inlineReply(List<String> mentions) {
        return new AuthorReplied(REPO, 412L, REVIEW_ID, new ThreadRef("finding-1"), "c-99",
                "and this one?", Author.of("human-1", "alice", "Alice"), false, mentions);
    }

    /** A bot-owned thread "finding-1" with {@code priorTurns} turns already spent. */
    private ConversationSaga sagaWith(int priorTurns, SpendGate gate) {
        ConversationSaga saga = new ConversationSaga();
        wireCredentials(saga);
        saga.threads = new ReviewThreadView() {
            @Override
            public ThreadRef rootOf(String reviewId, ThreadRef thread) {
                return new ThreadRef("finding-1");
            }

            @Override
            public boolean isOurThread(String reviewId, ThreadRef thread) {
                return true;
            }

            @Override
            public int turnCount(String reviewId, ThreadRef thread) {
                return priorTurns;
            }
        };
        saga.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
                timelineTypes.add(type);
                timelineDetails.add(detail);
            }
        };
        saga.projection = new ReviewProjection() {
            @Override
            public Optional<PriorRun> priorRunFor(String reviewId) {
                return Optional.empty();
            }

            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail,
                                    String threadRef) {
                appendedTypes.add(type);
                appendedThreadRefs.add(threadRef);
            }
        };
        saga.spendGate = gate;
        return saga;
    }

    /** Provider, level and credential wiring — identical for every test in this class. */
    private static void wireCredentials(ConversationSaga saga) {
        saga.reviewProviders = new ReviewProviderResolver() {
            @Override
            public Optional<ScmProvider> resolveForReview(String reviewId) {
                return Optional.of(new ScmProvider(UUID.randomUUID(), "GH", "github", "https://x",
                        "TEST-WS", "bearer", null, "secret", "acct", true, List.of(), "code-spire",
                        "EXPLAIN"));
            }
        };
        saga.levels = fixedLevel();
        saga.workerCredentials = new WorkerCredentials() {
            @Override
            public String pack(ScmProvider p) {
                return "TEST-PACKED-CREDENTIAL";
            }
        };
        saga.workerLlmCredentials = new WorkerLlmCredentials() {
            @Override
            public DefaultLlm resolveDefault(String workspace) {
                return DefaultLlm.spendable("TEST-LLM-CREDENTIAL", "TEST-MODEL");
            }
        };
        saga.promptTemplates = new WorkerPromptTemplates() {
            @Override
            public dev.codespire.contract.llm.PromptTemplate forKind(
                    dev.codespire.contract.llm.PromptKind kind) {
                return null;
            }
        };
    }

    /** INTERACTIVE so nothing but the caps under test can decline; the real bean reads settings. */
    private static ConversationLevels fixedLevel() {
        return new ConversationLevels() {
            @Override
            public ConversationLevel effectiveLevel(String type, String workspace) {
                return ConversationLevel.INTERACTIVE;
            }

            @Override
            public int turnCap() {
                return TURN_CAP;
            }

            @Override
            public int maxAttempts() {
                return 3;
            }

            @Override
            public long backoffBaseMs() {
                return 100L;
            }

            @Override
            public double backoffFactor() {
                return 2.0;
            }
        };
    }
}
