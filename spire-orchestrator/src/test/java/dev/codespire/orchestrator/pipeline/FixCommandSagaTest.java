package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent.ManualCommandReceived;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.policy.ReviewPolicy;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.provider.ReviewProviderResolver;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.readmodel.FindingProjection;
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
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code /fix} resolves the finding the thread belongs to, or refuses in a way the author can act on.
 *
 * <p><b>Its refusals SPEAK</b>, like {@code /finding}'s and unlike the authorization refusal. The
 * distinction is who is being answered: an unlisted author is a possible prober, and a reply tells
 * them the command is wired and costs an API call per probe. An author who cleared the allowlist and
 * used the command in the wrong place is a colleague, and silence sends them to hunt a lost webhook.
 *
 * <p>Fakes are hand-written and every method the path reaches is overridden. That is not politeness:
 * an un-overridden method on a saga fake opens a real {@code DataSource} from a plain unit test, and
 * this project has now hit that nine times — most recently in the round that fixed the eighth.
 */
class FixCommandSagaTest {


    private final List<String> notes = new ArrayList<>();
    private final List<String> noteDetails = new ArrayList<>();
    private final List<String> appendedEvents = new ArrayList<>();
    /**
     * The thread ref each durable row was filed under.
     *
     * <p>Recorded separately because the fixture overrides BOTH {@code appendEvent} overloads —
     * which is exactly why it read as safe — while both bodies discarded the one argument that
     * tells them apart. The real 5-arg method binds it into {@code review_event.thread_ref}, the
     * column the detail projection groups a conversation by, so filing the row under the branch
     * ref instead of the root silently ungroups it and passed every test.
     */
    private final List<String> appendedRefs = new ArrayList<>();

    /** What the finding lookup answers; null means "no finding on that thread". */
    private FindingProjection.TargetFinding target;
    private boolean registered = true;
    /**
     * Configured by default, because /fix now DENIES when it is empty.
     *
     * <p>An empty list is the deployment default and means "review everyone", which is why the two
     * cases that exercise the gate set it explicitly rather than relying on this.
     */
    private List<String> allowlist = List.of("alice");
    /** Thread refs the lookup was asked about — proves the saga normalized before querying. */
    private final List<String> lookedUpRefs = new ArrayList<>();

    private IntegrationSaga saga() {
        IntegrationSaga saga = new IntegrationSaga();
        saga.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
                notes.add(type);
                noteDetails.add(detail);
            }
        };
        saga.projection = new ReviewProjection() {
            @Override
            public boolean archived(String reviewId) {
                return false;
            }

            @Override
            public boolean registered(String reviewId) {
                return registered;
            }

            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail) {
                appendedEvents.add(type + ":" + detail);
                appendedRefs.add(null);
            }

            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail, String ref) {
                appendedEvents.add(type + ":" + detail);
                appendedRefs.add(ref);
            }
        };
        // Normalizes to the conversation root, as every sibling path in the saga does: on an SCM that
        // threads by immediate parent, a command typed in a reply carries THAT reply's id, and the
        // finding hangs off the root.
        saga.threads = new ReviewThreadView() {
            @Override
            public ThreadRef rootOf(String reviewId, ThreadRef thread) {
                return new ThreadRef("root-" + thread.value());
            }
        };
        saga.findings = new FindingProjection() {
            @Override
            public Optional<TargetFinding> findByThread(String reviewId, String threadRef) {
                lookedUpRefs.add(threadRef);
                return Optional.ofNullable(target);
            }
        };
        saga.reviewProviders = new ReviewProviderResolver() {
            @Override
            public Optional<ScmProvider> resolveForReview(String reviewId) {
                return provider();
            }
        };
        saga.providers = new ProviderRegistry() {
            @Override
            public Optional<ScmProvider> resolveByWorkspace(String workspace) {
                return provider();
            }
        };
        saga.policy = new ReviewPolicy() {
            @Override
            public boolean observeOnly() {
                return false;
            }
        };
        return saga;
    }

    private Optional<ScmProvider> provider() {
        return Optional.of(new ScmProvider(UUID.randomUUID(), "CF", "bitbucket-cloud", "https://x", "acme",
                "bearer", null, "secret", "acct", true, allowlist, null, null));
    }

    private static ManualCommandReceived fix(String threadRef) {
        return new ManualCommandReceived(new RepoRef("acme", "web"), 412L, "fix", "",
                Author.of("acc-1", "alice", "Alice"), new ThreadRef(threadRef), null, "c-1");
    }

    /** Distinct start and end lines: equal ones make the two components interchangeable. */
    private static FindingProjection.TargetFinding finding(String verdict) {
        return finding(verdict, "review");
    }

    private static FindingProjection.TargetFinding finding(String verdict, String origin) {
        return new FindingProjection.TargetFinding(77L, 2, "src/Foo.java", 44, 48, "HIGH", verdict, origin);
    }

    @Test
    void resolvesTheFindingTheThreadBelongsToAndRecordsTheRequest() {
        target = finding(null);
        saga().on(fix("t-1"));
        assertTrue(notes.contains("FixRequested"), notes.toString());
        // The finding is NAMED, and asserted whole. Checking only that the path appears left the
        // severity and the start line free to be dropped or swapped for the end line.
        assertTrue(noteDetails.contains("HIGH at src/Foo.java:44"), noteDetails.toString());
        // Exact, because the durable row is the record of a human asking for money to be spent —
        // so WHO asked is part of it, and a startsWith check asserted none of that.
        assertTrue(appendedEvents.contains("FixRequested:@alice asked for a fix: HIGH at src/Foo.java:44"),
                appendedEvents.toString());
        assertEquals(List.of("root-t-1"), appendedRefs,
                "the durable row is filed under the conversation root, or it groups with nothing");
    }

    /**
     * The lookup is asked about the conversation ROOT, not the comment the command was typed in.
     * Bitbucket threads by immediate parent, so a {@code /fix} typed as a reply to the bot's own
     * answer carries that answer's id — and the finding hangs off the root. Keying off the raw ref
     * would find nothing and refuse a valid command.
     */
    @Test
    void looksTheFindingUpByTheConversationRootNotTheCommentItWasTypedIn() {
        target = finding(null);
        saga().on(fix("t-1"));
        assertEquals(List.of("root-t-1"), lookedUpRefs);
    }

    /**
     * The message must not assert that no finding exists, because on Bitbucket that is false.
     * That SCM threads by immediate parent and only the bot's comments get a review_thread row,
     * so a /fix typed as a reply to another HUMAN's reply matches nothing while the finding sits
     * visibly a few comments up. It says what it could not do, not what is not there.
     */
    @Test
    void refusesWhenNoFindingHangsOffThatThread() {
        target = null;
        saga().on(fix("t-1"));
        assertTrue(notes.contains("refused:/fix"), notes.toString());
        assertFalse(notes.contains("FixRequested"), notes.toString());
        assertTrue(noteDetails.stream().anyMatch(d -> d.contains("could not match this thread")),
                noteDetails.toString());
        assertTrue(noteDetails.stream().noneMatch(d -> d.contains("no finding on this thread")),
                "must not claim the finding does not exist — it may, on a parent-threaded SCM");
    }

    /**
     * A command typed as a top-level PR comment carries no thread, so there is nothing to resolve.
     * Refused BEFORE {@code rootOf}, which binds its argument into a statement immediately — a null
     * there throws an NPE inside a {@code catch (SQLException)} that cannot see it, which is the
     * trap ADR-024's archived notice already paid for.
     */
    @Test
    void refusesAtopLevelFixWithNoThreadRatherThanThrowing() {
        target = finding(null);
        saga().on(new ManualCommandReceived(new RepoRef("acme", "web"), 412L, "fix", "",
                Author.of("acc-1", "alice", "Alice"), null, null, "c-1"));
        assertTrue(notes.contains("skipped:/fix"), notes.toString());
        assertTrue(lookedUpRefs.isEmpty(), "nothing should be looked up without a thread");
    }

    /** Fixing what is already fixed spends an agent run to produce an empty diff. */
    @Test
    void refusesAFindingReconciliationHasAlreadyResolved() {
        target = finding("RESOLVED");
        saga().on(fix("t-1"));
        assertTrue(notes.contains("refused:/fix"), notes.toString());
        assertFalse(notes.contains("FixRequested"), notes.toString());
        assertTrue(noteDetails.stream().anyMatch(d -> d.contains("already resolved")), noteDetails.toString());
    }

    /** A verdict that is not RESOLVED still names an open finding — only RESOLVED closes the door. */
    @Test
    void stillFixesAFindingJudgedStillOpen() {
        target = finding("STILL_OPEN");
        saga().on(fix("t-1"));
        assertTrue(notes.contains("FixRequested"), notes.toString());
    }

    /**
     * An unregistered pull request clears every gate ahead of this one — {@code archived} answers
     * false for a row that does not exist, and the provider resolves by workspace when the review
     * has no stored type. {@code /finding} learned this the hard way; {@code /fix} inherits the
     * check rather than the lesson.
     */
    /**
     * A {@code /finding}-filed row carries NULL message and suggestion by design (DATA-MODEL §5),
     * so FR-F27's "complete task specification" would be a severity, a path and a line. Refused
     * here rather than left for the dispatch, which by then has accepted the target and can only
     * pay for a run on an empty spec or retract.
     */
    @Test
    void refusesAFindingFiledFromADiscussionBecauseItCarriesNoDescription() {
        target = finding(null, "conversation");
        saga().on(fix("t-1"));
        assertTrue(notes.contains("refused:/fix"), notes.toString());
        assertFalse(notes.contains("FixRequested"), notes.toString());
        assertTrue(noteDetails.stream().anyMatch(d -> d.contains("no description")),
                noteDetails.toString());
    }

    /**
     * Deny by default, for this command only.
     *
     * <p>An empty provider allowlist means "review everyone" deliberately, which is right for one
     * spend-capped model call and wrong for a command that pushes code as the machine account.
     * {@code AUTONOMY.md} Rule 3 names the threat directly. The sibling case below is the half
     * that keeps this from being a blanket refusal.
     */
    @Test
    void refusesFixWhenTheProviderAllowlistIsEmpty() {
        allowlist = List.of();
        target = finding(null);
        saga().on(fix("t-1"));
        assertTrue(notes.contains("refused:/fix"), notes.toString());
        assertTrue(lookedUpRefs.isEmpty(), "an unlisted deployment must not even be queried");
    }

    /** The other half: a configured allowlist still admits the command. */
    @Test
    void allowsFixWhenTheProviderAllowlistIsConfigured() {
        allowlist = List.of("alice");
        target = finding(null);
        saga().on(fix("t-1"));
        assertTrue(notes.contains("FixRequested"), notes.toString());
    }

    @Test
    void refusesWhenThePullRequestWasNeverRegistered() {
        registered = false;
        target = finding(null);
        saga().on(fix("t-1"));
        assertTrue(notes.contains("skipped:/fix"), notes.toString());
        assertTrue(lookedUpRefs.isEmpty(), "an unregistered PR must not even be queried");
    }
}
