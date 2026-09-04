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
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.policy.ReviewPolicy;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.provider.ReviewProviderResolver;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.provider.ProviderRole;
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
 * The observe-mode gate and the per-provider author allowlist in
 * {@link IntegrationSaga}. Collaborators are field-injected, so the test sets
 * hand-written fakes directly — no CDI container, no mocking framework.
 */
class IntegrationSagaPolicyTest {

    private final List<ActionCommand> emitted = new ArrayList<>();
    private final List<String> notes = new ArrayList<>();
    /** Each timeline note as {@code lane|detail} — the halves the type alone does not pin. */
    private final List<String> noteDetails = new ArrayList<>();
    private final List<String> headerProviderTypes = new ArrayList<>();
    private final List<String> refreshedProviderTypes = new ArrayList<>();
    private final List<String> rerunInvocations = new ArrayList<>();
    private final List<RecordCommand> handledCommands = new ArrayList<>();
    private final List<String> prStateCalls = new ArrayList<>();
    /** Fork provenance written per event, as {@code reviewId:fromFork}. */
    private final List<String> fromForkCalls = new ArrayList<>();
    /** Durable review-history rows, as {@code type:detail} — distinct from the in-memory timeline. */
    private final List<String> appendedEvents = new ArrayList<>();
    private boolean reviewRegistered;
    /** Set by the archived-notice cases; every other case runs against a live review. */
    private boolean reviewIsArchived;

    private IntegrationSaga sagaWith(ReviewPolicy policy, Optional<ScmProvider> provider) {
        return sagaWith(policy, provider, List.of(new DomainEvent.ReviewRequested("cafe123", "OPENED")));
    }

    /**
     * {@code lifecycleResult} is what the (faked) decider returns — an empty list
     * models the real idempotency no-op for a re-delivered same-commit event.
     */
    private IntegrationSaga sagaWith(ReviewPolicy policy, Optional<ScmProvider> provider,
                                     List<DomainEvent> lifecycleResult) {
        IntegrationSaga saga = new IntegrationSaga();
        saga.lifecycle = new ReviewLifecycleService() {
            @Override
            public List<DomainEvent> handle(String reviewId, RecordCommand command) {
                reviewRegistered = true;
                handledCommands.add(command);
                return lifecycleResult;
            }
        };
        saga.commands = new CommandsEmitter() {
            @Override
            public void emit(ActionCommand command) {
                emitted.add(command);
            }
        };
        saga.timeline = new TimelineBroadcaster() {
            @Override
            public void record(String lane, String type, String reviewId, String detail) {
                notes.add(type);
                // Lane and detail too. Recording only the type left them pinned by nothing, so
                // moving the note to another lane and blanking its text passed every case — and for
                // a refusal that is DELIBERATELY silent the note is the operator's only signal, so
                // its text is the feature rather than decoration.
                noteDetails.add(lane + "|" + detail);
            }
        };
        saga.projection = new ReviewProjection() {
            /** Every event passes the archival gate first; these fixtures are live unless a case says otherwise. */
            @Override
            public boolean archived(String reviewId) {
                return reviewIsArchived;
            }

            /**
             * Registered, so a gated command has a REACHABLE handler behind the gate.
             *
             * <p>Without this the mutation that deletes the gate dies on an NPE instead of an
             * assertion: the real method calls {@code dataSource.getConnection()} on a null field,
             * and {@code catch (SQLException)} cannot see an NPE. That is this project's recorded
             * fake-coverage trap for the eighth time, and it made the {@code /finding} case red for
             * the wrong reason — proving the path was not taken without proving the gate stopped it.
             */
            @Override
            public boolean registered(String reviewId) {
                return true;
            }

            /** Nothing posted, so a refusal has no summary thread to fall back to. */
            @Override
            public Optional<String> summaryRefOf(String reviewId) {
                return Optional.empty();
            }

            @Override
            public void registerHeader(String reviewId, RepoRef repo, long prId, String title, String author,
                                       String authorId, String sourceBranch, String destBranch, String sha,
                                       String htmlUrl, String providerType, String status, int stage) {
                headerProviderTypes.add(providerType);
            }

            @Override
            public void refreshHeader(String reviewId, RepoRef repo, long prId, String title, String author,
                                     String authorId, String sourceBranch, String destBranch, String sha,
                                     String htmlUrl, String providerType) {
                refreshedProviderTypes.add(providerType);
            }

            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail) {
                appendedEvents.add(type + ":" + detail);
            }

            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail, String threadRef) {
                appendedEvents.add(type + ":" + detail);
            }

            @Override
            public void setNote(String reviewId, String note) {
            }

            @Override
            public void touch(String reviewId) {
            }

            @Override
            public void setPrState(String reviewId, String prState) {
                prStateCalls.add(reviewId + ":" + prState);
            }

            /**
             * Fork provenance, written on every pull-request event.
             *
             * <p>Instance TEN of this project's recorded fake-coverage trap: un-overridden, the real
             * method opens a {@code DataSource} from a plain unit test. It arrived the same way the
             * previous nine did — a new production write on a path these fixtures already exercised,
             * added by someone who did not re-read which methods the path reaches.
             */
            @Override
            public void setFromFork(String reviewId, boolean fromFork) {
                fromForkCalls.add(reviewId + ":" + fromFork);
            }

            /**
             * Reached only once a reply is actually answered, which no case did until the observe
             * gate needed an active-mode twin. Un-overridden it opens a real {@code DataSource} from
             * a plain unit test — the trap this project has now hit nine times, and the ninth was
             * this fixture, in the round that fixed the eighth.
             */
            @Override
            public void setAnswering(String reviewId, boolean answering) {
            }
        };
        // Third un-overridden method on the path a deleted gate would take. Left null it reaches a
        // null DataSource exactly as the projection fake did, so the NPE would simply move.
        saga.threads = new ReviewThreadView() {
            @Override
            public ThreadRef rootOf(String reviewId, ThreadRef thread) {
                return thread;
            }
        };
        saga.providers = new ProviderRegistry() {
            @Override
            public Optional<ScmProvider> resolveByWorkspace(String workspace) {
                return provider;
            }
        };
        // The self-loop guard resolves the review's bot by the review's stored SCM type.
        saga.reviewProviders = new ReviewProviderResolver() {
            @Override
            public Optional<ScmProvider> resolveForReview(String reviewId) {
                return provider;
            }
        };
        saga.workerCredentials = new WorkerCredentials() {
            @Override
            public String pack(ScmProvider p) {
                return "packed-cred:" + p.workspace();
            }
        };
        // Always answers, so the reply cases test the GATE rather than the conversation policy.
        // A fake that declined would make the observe assertion pass for the wrong reason.
        saga.conversation = new ConversationSaga() {
            @Override
            public Optional<ActionCommand> planFollowUp(AuthorReplied e) {
                return Optional.of(new ActionCommand.AnswerFollowUp(e.reviewId(), e.repo(), e.prId(),
                        e.threadRef(), e.commentId(), e.text(), "scm-cred", "llm-cred", false, 1, 100L, 2.0));
            }
        };
        saga.policy = policy;
        saga.rerunService = new ReviewRerunService() {
            @Override
            public boolean rerun(String workspace, String slug, long pr) {
                rerunInvocations.add(workspace + "/" + slug + "#" + pr);
                return true;
            }
        };
        return saga;
    }

    private static Optional<ScmProvider> provider(List<String> authors) {
        return Optional.of(new ScmProvider(UUID.randomUUID(), "CF", "bitbucket-cloud", "https://x", "acme",
                "bearer", null, "secret", "acct", true, authors, null, null, ProviderRole.REVIEWER));
    }

    /** A ReviewPolicy fake with a fixed mode — the saga only reads observeOnly(). */
    private static ReviewPolicy policyMode(boolean observeOnly) {
        return new ReviewPolicy() {
            @Override
            public boolean observeOnly() {
                return observeOnly;
            }
        };
    }

    private static PullRequestEventReceived pr(String accountId, String username) {
        return pr(accountId, username, null);
    }

    private static PullRequestEventReceived pr(String accountId, String username, String providerType) {
        return new PullRequestEventReceived(
                new RepoRef("acme", "web"), 412L, PrAction.OPENED,
                "Refactor checkout", "desc", "feature", "main",
                "cafe123",
                Author.of(accountId, username, "Display Name"),
                "https://example/pr/412", providerType);
    }

    @Test
    void eventWithProviderType_resolvesByTypeNotWorkspaceAlone() {
        // Two SCMs can share a workspace name; when the event names its type the saga
        // MUST resolve by (type, workspace). resolveByWorkspace throwing proves it isn't
        // used as the fallback here.
        IntegrationSaga saga = sagaWith(policyMode(false), provider(List.of()));
        saga.providers = new ProviderRegistry() {
            @Override
            public Optional<ScmProvider> resolve(String type, String workspace) {
                return Optional.of(new ScmProvider(UUID.randomUUID(), "BB", type, "https://x", workspace,
                        "bearer", null, "secret", "acct", true, List.of(), null, null, ProviderRole.REVIEWER));
            }

            @Override
            public Optional<ScmProvider> resolveByWorkspace(String workspace) {
                throw new AssertionError("must resolve by (type, workspace), not workspace alone");
            }
        };

        saga.on(pr("acct", "user", "bitbucket-cloud"));

        assertTrue(headerProviderTypes.contains("bitbucket-cloud"), "resolved by the event's SCM type");
        assertTrue(emitted.stream().anyMatch(ActionCommand.FetchDiff.class::isInstance),
                "an active review dispatched FetchDiff via the type-resolved provider");
    }

    @Test
    void noProviderRegistered_skippedEntirely() {
        var saga = sagaWith(policyMode(false), Optional.empty());
        saga.on(pr("acc-1", "alice"));
        assertFalse(reviewRegistered);
        assertTrue(emitted.isEmpty());
        assertTrue(notes.contains("PullRequestSkipped"));
    }

    @Test
    void authorNotInProviderAllowlist_skipped() {
        var saga = sagaWith(policyMode(false), provider(List.of("alice")));
        saga.on(pr("acc-9", "bob"));
        assertFalse(reviewRegistered);
        assertTrue(emitted.isEmpty());
        assertTrue(notes.contains("PullRequestSkipped"));
    }

    @Test
    void emptyProviderAllowlist_reviewsEveryone() {
        var saga = sagaWith(policyMode(false), provider(List.of()));
        saga.on(pr("acc-1", "anyone"));
        assertTrue(reviewRegistered);
        assertEquals(1, emitted.size());
        assertInstanceOf(ActionCommand.FetchDiff.class, emitted.get(0));
    }

    @Test
    void active_allowlistedAuthor_emitsFetchDiff() {
        var saga = sagaWith(policyMode(false), provider(List.of("alice")));
        saga.on(pr("acc-1", "alice"));
        assertEquals(1, emitted.size());
        assertInstanceOf(ActionCommand.FetchDiff.class, emitted.get(0));
    }

    @Test
    void allowlistMatchesByAccountId() {
        var saga = sagaWith(policyMode(false), provider(List.of("712020:d1005216")));
        saga.on(pr("712020:d1005216", "any-nickname"));
        assertEquals(1, emitted.size());
    }

    @Test
    void registerHeader_carriesTheResolvedProviderType() {
        var saga = sagaWith(policyMode(false), provider(List.of("alice")));
        saga.on(pr("acc-1", "alice"));
        assertEquals(List.of("bitbucket-cloud"), headerProviderTypes,
                "the registered provider's type is projected onto the review row (C7)");
    }

    @Test
    void botAuthoredCommand_isDroppedBySelfLoopGuard() {
        // The bot's account id is the provider's botAccountId ("acct") — the guard
        // moved from the gateway to here, resolving it from the registry.
        var saga = sagaWith(policyMode(false), provider(List.of()));
        saga.on(new ManualCommandReceived(new RepoRef("acme", "web"), 412L, "review", "",
                Author.of("acct", "spire-bot", "Bot")));
        assertTrue(notes.contains("SelfLoopDropped"), "bot-authored /command is dropped (self-loop guard)");
        assertTrue(emitted.isEmpty());
    }

    @Test
    void humanAuthoredCommand_isNotDroppedBySelfLoopGuard() {
        var saga = sagaWith(policyMode(false), provider(List.of()));
        saga.on(new ManualCommandReceived(new RepoRef("acme", "web"), 412L, "review", "",
                Author.of("human-1", "alice", "Alice")));
        assertFalse(notes.contains("SelfLoopDropped"), "a human /command is not a self-loop");
    }

    @Test
    void reviewCommandTriggersARerun() {
        var saga = sagaWith(policyMode(false), provider(List.of()));
        saga.on(new ManualCommandReceived(new RepoRef("acme", "web"), 412L, "review", "",
                Author.of("human-1", "alice", "Alice")));
        assertEquals(List.of("acme/web#412"), rerunInvocations, "the rerun service is called with repo + PR");
        assertFalse(notes.contains("SelfLoopDropped"), "a human /review is not a self-loop");
    }

    /**
     * A {@code /review} comment spends real money — the rerun deliberately clears the worker's LLM
     * idempotency claim so the model runs again — and nothing else bounds this path
     * ({@code SPIRE_REVIEW_MAX_ATTEMPTS} bounds auto-retry, the turn cap bounds follow-ups). So the
     * same per-provider allowlist that gates a PR event has to gate the command, or anyone who can
     * comment on the PR can bill the operator once per comment.
     *
     * <p>No reply is posted to the refused author, and that is deliberate: answering confirms to a
     * prober that the command exists and is wired, and turns each probe into a comment the operator
     * pays an API call to post. The refusal is a timeline note plus a log line, exactly as the PR-open
     * path records an unlisted author.
     */
    @Test
    void reviewCommandFromAnAuthorOutsideTheAllowlistNeverReachesTheRerunService() {
        var saga = sagaWith(policyMode(false), provider(List.of("alice")));
        saga.on(new ManualCommandReceived(new RepoRef("acme", "web"), 412L, "review", "",
                Author.of("acc-9", "bob", "Bob")));
        assertTrue(rerunInvocations.isEmpty(), "an unlisted author's /review must not spend an LLM call");
        assertTrue(notes.contains("ManualCommandSkipped"), notes.toString());
    }

    /** The other half: the gate must refuse the unlisted author, not close the path for everyone. */
    @Test
    void reviewCommandFromAnAllowlistedAuthorStillRuns() {
        var saga = sagaWith(policyMode(false), provider(List.of("alice")));
        saga.on(new ManualCommandReceived(new RepoRef("acme", "web"), 412L, "review", "",
                Author.of("acc-1", "alice", "Alice")));
        assertEquals(List.of("acme/web#412"), rerunInvocations, "a listed author's /review still re-runs");
        assertFalse(notes.contains("ManualCommandSkipped"), notes.toString());
    }

    /**
     * An empty allowlist reviews everyone (existing, deliberate: an operator who lists nobody has
     * opted every author in). The gate must not reinterpret that as "nobody".
     */
    @Test
    void reviewCommandUnderAnEmptyAllowlistStillRuns() {
        var saga = sagaWith(policyMode(false), provider(List.of()));
        saga.on(new ManualCommandReceived(new RepoRef("acme", "web"), 412L, "review", "",
                Author.of("acc-1", "anyone", "Anyone")));
        assertEquals(List.of("acme/web#412"), rerunInvocations);
        assertFalse(notes.contains("ManualCommandSkipped"), notes.toString());
    }

    /**
     * Observe mode's contract is "register only, no diff, no LLM, no comments". Every {@code /command}
     * reached {@code onManualCommand}, which never consulted the policy — so an operator evaluating a
     * deployment could still be billed for a paid re-review by anyone allowlisted enough to type
     * {@code /review} in a pull request.
     *
     * <p><b>The refusal is silent, and here that is forced rather than chosen.</b> Every other silent
     * refusal in this saga argues for its silence (a reply confirms to a prober that a command is
     * wired). This one could not reply even if that reasoning were absent: posting a comment is the
     * exact thing observe mode forbids, so answering would break the mode in the act of enforcing it.
     */
    /**
     * Fork provenance is persisted from EVERY pull-request event, in every mode.
     *
     * <p>Asserted because the write is what a branch-mode gate later trusts, and a row that predates
     * V55 defaults to false — so a saga that recorded nothing would leave a guess in place of an
     * answer, and the guess is the permissive one.
     */
    @Test
    void everyPullRequestEventRecordsWhetherItCameFromAFork() {
        var saga = sagaWith(policyMode(false), provider(List.of()));
        saga.on(pr("acc-1", "alice").withFromFork(true));
        assertTrue(fromForkCalls.contains("review::acme/web#412:true"), fromForkCalls.toString());

        fromForkCalls.clear();
        sagaWith(policyMode(true), provider(List.of())).on(pr("acc-1", "alice"));
        assertTrue(fromForkCalls.contains("review::acme/web#412:false"),
                "observe mode registers the header, so it records this too: " + fromForkCalls);
    }

    @Test
    void reviewCommandIsRefusedInObserveMode() {
        var saga = sagaWith(policyMode(true), provider(List.of()));
        saga.on(new ManualCommandReceived(new RepoRef("acme", "web"), 412L, "review", "",
                Author.of("acc-1", "alice", "Alice")));
        assertTrue(rerunInvocations.isEmpty(), "observe mode must not spend an LLM call on /review");
        assertTrue(notes.contains("ManualCommandObserveOnly"), notes.toString());
        // The note's lane and text, not only its type. The refusal is deliberately silent, so this
        // note is the operator's only signal and naming the refused command IS the feature.
        assertTrue(noteDetails.contains("integration|/review refused: the deployment is in observe-only mode"),
                noteDetails.toString());
        // The converse of the ordering case below: an ALLOWED author must not be reported as
        // unauthorized. Asserted in both directions, or the two refusals may quietly become one.
        assertFalse(notes.contains("ManualCommandSkipped"), notes.toString());
        // Durable, so the reason survives the restart that empties the in-memory timeline.
        assertTrue(appendedEvents.stream().anyMatch(row -> row.startsWith("ManualCommandObserveOnly:")),
                appendedEvents.toString());
    }

    /**
     * The gate is proven on a CONFIGURED allowlist, not only an empty one.
     *
     * <p>Every other observe case passes an empty list, so a gate ANDed with "the allowlist is
     * empty" satisfied all of them — while being inert on every deployment past first contact. The
     * ordering case below cannot cover it either: its author is refused by the allowlist one branch
     * earlier and never reaches the mode gate at all.
     */
    @Test
    void anAllowlistedAuthorIsStillRefusedInObserveMode() {
        var saga = sagaWith(policyMode(true), provider(List.of("alice")));
        saga.on(new ManualCommandReceived(new RepoRef("acme", "web"), 412L, "review", "",
                Author.of("acc-1", "alice", "Alice")));
        assertTrue(rerunInvocations.isEmpty(), "a listed author's /review is still refused in observe mode");
        assertTrue(notes.contains("ManualCommandObserveOnly"), notes.toString());
    }

    /**
     * The same gate, proven on the second command rather than assumed from the first. {@code /finding}
     * spends no model call, so the "it is the operator's own paid command" reading that might excuse
     * {@code /review} does not reach it — it is a real aggregate write and a real posted confirmation
     * under a mode whose whole point is look-but-do-not-touch.
     */
    @Test
    void findingCommandIsRefusedInObserveMode() {
        var saga = sagaWith(policyMode(true), provider(List.of()));
        saga.on(new ManualCommandReceived(new RepoRef("acme", "web"), 412L, "finding", "sev=HIGH",
                Author.of("acc-1", "alice", "Alice")));
        assertTrue(handledCommands.isEmpty(), "observe mode must not advance the aggregate");
        // The only durable row is the refusal itself — the finding must not be recorded.
        assertEquals(List.of("ManualCommandObserveOnly:/finding refused — observe-only mode"),
                appendedEvents, "observe mode must record the refusal and nothing else");
        assertTrue(notes.contains("ManualCommandObserveOnly"), notes.toString());
    }

    /**
     * A command with no handler is refused too, so the gate covers commands this milestone has not
     * added yet rather than only the three that exist today.
     *
     * <p><b>The command name must be one the switch does NOT handle, and this test lost that once
     * already.</b> It was written driving {@code "fix"} while {@code /fix} had no handler; two
     * commits later {@code /fix} gained one, and the case silently became a test of an ENUMERATED
     * command — its stated guarantee asserted by nothing, while staying green. Verified by
     * narrowing the gate to the enumerated set: with {@code "fix"} the suite still passed.
     *
     * <p>It does NOT pin "the gate precedes the switch": replicating the gate inside every
     * {@code case} arm passes this. That placement is structural, argued at the call site, and
     * contrived to regress; what is worth asserting is that an unenumerated command is covered.
     */
    @Test
    void aCommandWithNoHandlerIsAlsoRefusedInObserveMode() {
        var saga = sagaWith(policyMode(true), provider(List.of()));
        saga.on(new ManualCommandReceived(new RepoRef("acme", "web"), 412L, "nonesuch", "",
                Author.of("acc-1", "alice", "Alice")));
        assertTrue(notes.contains("ManualCommandObserveOnly"),
                "the gate must precede the switch, or every future command inherits the hole again");
    }

    /**
     * The other half. A gate that refuses everything passes every test above and closes the feature —
     * so the active-mode path is asserted with the same fixture, differing only in the policy.
     */
    @Test
    void commandsAreNotRefusedWhenTheDeploymentIsActive() {
        var saga = sagaWith(policyMode(false), provider(List.of()));
        saga.on(new ManualCommandReceived(new RepoRef("acme", "web"), 412L, "review", "",
                Author.of("acc-1", "alice", "Alice")));
        assertEquals(List.of("acme/web#412"), rerunInvocations, "an active deployment still re-runs");
        assertFalse(notes.contains("ManualCommandObserveOnly"), notes.toString());
    }

    /**
     * Ordering, asserted rather than left to reading order. An unlisted author in observe mode is
     * refused as UNAUTHORIZED, not as passive: the allowlist answers whether this person's command
     * counts at all, and reporting the deployment's mode instead would tell an operator the wrong
     * thing about why nothing happened.
     */
    @Test
    void anUnlistedAuthorInObserveModeIsRefusedByTheAllowlistNotTheModeGate() {
        var saga = sagaWith(policyMode(true), provider(List.of("alice")));
        saga.on(new ManualCommandReceived(new RepoRef("acme", "web"), 412L, "review", "",
                Author.of("acc-9", "bob", "Bob")));
        assertTrue(notes.contains("ManualCommandSkipped"), notes.toString());
        assertFalse(notes.contains("ManualCommandObserveOnly"), notes.toString());
    }

    /**
     * The widest path of the three, and the one the command gate could never reach.
     *
     * <p>A reply becomes eligible on an @-mention ALONE, independent of thread ownership, and an
     * @-mention also removes the per-thread turn cap — so where {@code /review} lost one call, this
     * loses an unbounded number. The realistic exposure is not a fresh deployment (the conversation
     * level defaults to report-only) but the operator gesture the mode exists for: running active,
     * then flipping the slider to observe to pause the bot. Every thread is still bot-owned then.
     */
    @Test
    void aReplyIsNotAnsweredInObserveMode() {
        var saga = sagaWith(policyMode(true), provider(List.of()));
        saga.on(reply("acc-1", "alice"));
        assertTrue(emitted.isEmpty(), "observe mode must not spend an LLM call answering a reply");
        assertTrue(notes.contains("FollowUpObserveOnly"), notes.toString());
    }

    /** The other half: the gate must pause the bot, not silence the conversation permanently. */
    @Test
    void aReplyIsStillAnsweredWhenTheDeploymentIsActive() {
        var saga = sagaWith(policyMode(false), provider(List.of()));
        saga.on(reply("acc-1", "alice"));
        assertEquals(1, emitted.size(), "an active deployment still answers");
        assertInstanceOf(ActionCommand.AnswerFollowUp.class, emitted.getFirst());
        assertFalse(notes.contains("FollowUpObserveOnly"), notes.toString());
    }

    /**
     * The archived notice is a posted comment, so observe mode forbids it — and this path is
     * upstream of every other gate. {@code handle()} stops an archived review before the switch, so
     * no gate inside {@code onManualCommand} could reach it however it were placed.
     */
    @Test
    void theArchivedNoticeIsNotPostedInObserveMode() {
        reviewIsArchived = true;
        var saga = sagaWith(policyMode(true), provider(List.of()));
        saga.on(new ManualCommandReceived(new RepoRef("acme", "web"), 412L, "review", "",
                Author.of("acc-1", "alice", "Alice")));
        assertTrue(emitted.isEmpty(), "observe mode posts no comments, and the notice is a comment");
    }

    /** The other half, or the gate would simply delete the notice. */
    @Test
    void theArchivedNoticeIsStillPostedWhenTheDeploymentIsActive() {
        reviewIsArchived = true;
        var saga = sagaWith(policyMode(false), provider(List.of()));
        saga.on(new ManualCommandReceived(new RepoRef("acme", "web"), 412L, "review", "",
                Author.of("acc-1", "alice", "Alice")));
        assertEquals(1, emitted.size(), "an active deployment still tells the author the PR is retired");
        assertInstanceOf(ActionCommand.NotifyArchived.class, emitted.getFirst());
    }

    /**
     * The contract, asserted over the EVENT VOCABULARY rather than per branch.
     *
     * <p>{@code ReviewPolicy}'s javadoc says observe "emits NO action commands", and three separate
     * paths broke that while each individual branch looked correct: the command switch, the reply
     * branch, and the archived notice that runs ahead of both. Per-branch tests found them one at a
     * time, which is how the second and third survived the round that fixed the first.
     *
     * <p>So this asserts the property over every ingress event the saga handles, live and archived.
     * A branch added later inherits the assertion instead of needing someone to remember it — the
     * same shape as the guards that fail on a debt's REINTRODUCTION rather than its removal.
     */
    @Test
    void observeModeEmitsNoActionCommandForAnyIngressEvent() {
        List<IntegrationEvent> everyTrigger = List.of(
                pr("acc-1", "alice"),
                new ManualCommandReceived(new RepoRef("acme", "web"), 412L, "review", "",
                        Author.of("acc-1", "alice", "Alice")),
                new ManualCommandReceived(new RepoRef("acme", "web"), 412L, "finding", "sev=HIGH",
                        Author.of("acc-1", "alice", "Alice")),
                reply("acc-1", "alice"),
                new PullRequestClosed(new RepoRef("acme", "web"), 412L, CloseReason.MERGED));

        for (boolean archived : List.of(false, true)) {
            for (IntegrationEvent event : everyTrigger) {
                emitted.clear();
                reviewIsArchived = archived;
                sagaWith(policyMode(true), provider(List.of())).on(event);
                assertTrue(emitted.isEmpty(),
                        "observe mode emitted " + emitted + " for " + event.getClass().getSimpleName()
                                + " (archived=" + archived + ") — the mode's contract is that it emits none");
            }
        }
    }

    /**
     * Guards the guard. An event list that silently lost a case would keep the assertion above green
     * while covering less, which is the failure mode the parity tests in the gateway already name.
     */
    @Test
    void theNoActionCommandCaseCoversEveryHandledEventType() {
        assertEquals(List.of("AuthorReplied", "ManualCommandReceived", "PullRequestClosed",
                        "PullRequestEventReceived"),
                List.of(pr("acc-1", "alice"),
                                new ManualCommandReceived(new RepoRef("acme", "web"), 412L, "review", "",
                                        Author.of("acc-1", "alice", "Alice")),
                                reply("acc-1", "alice"),
                                new PullRequestClosed(new RepoRef("acme", "web"), 412L, CloseReason.MERGED))
                        .stream().map(e -> e.getClass().getSimpleName()).distinct().sorted().toList(),
                "the saga's switch handles four event types; the observe case must cover all four");
    }

    private static AuthorReplied reply(String accountId, String username) {
        return new AuthorReplied(new RepoRef("acme", "web"), 412L, "review::acme/web#412",
                new ThreadRef("t-1"), "c-1", "@bot why is this a bug?",
                Author.of(accountId, username, username), false, List.of("bot"));
    }

    @Test
    void reviewCommandOnUnknownPrIsSkippedNotFatal() {
        var saga = sagaWith(policyMode(false), provider(List.of()));
        saga.rerunService = new ReviewRerunService() {
            @Override
            public boolean rerun(String workspace, String slug, long pr) {
                throw new jakarta.ws.rs.NotFoundException("no review for " + workspace + "/" + slug + "#" + pr);
            }
        };
        saga.on(new ManualCommandReceived(new RepoRef("acme", "web"), 412L, "review", "",
                Author.of("human-1", "alice", "Alice")));
        assertTrue(notes.contains("skipped:/review"), "an unknown PR is recorded as skipped, not fatal");
    }

    @Test
    void botAuthoredReviewCommandStaysDropped() {
        var saga = sagaWith(policyMode(false), provider(List.of()));
        saga.on(new ManualCommandReceived(new RepoRef("acme", "web"), 412L, "review", "",
                Author.of("acct", "spire-bot", "Bot")));
        assertTrue(notes.contains("SelfLoopDropped"), "bot-authored /review is still dropped by the self-loop guard");
        assertTrue(rerunInvocations.isEmpty(), "the rerun service is never called for a bot-authored /review");
    }

    @Test
    void redeliveredSameCommit_refreshesMetadataWithoutClaimingARun() {
        // The decider no-ops a same-commit re-delivery (proven in ReviewLifecycleTest), so no run starts.
        // Claiming "reviewing" here overwrote a COMPLETED review and, with nothing dispatched, left it
        // stuck in "reviewing" forever — observed live after a provider's webhook "test" delivery.
        var saga = sagaWith(policyMode(false), provider(List.of("alice")), List.of());
        saga.on(pr("acc-1", "alice"));

        assertTrue(headerProviderTypes.isEmpty(), "must not re-claim status/stage for a review already done");
        assertEquals(List.of("bitbucket-cloud"), refreshedProviderTypes,
                "the PR metadata is still refreshed, leaving the existing outcome intact");
        assertTrue(emitted.isEmpty(), "a re-delivered same-commit event dispatches no FetchDiff");
    }

    @Test
    void firstDeliveryClaimsTheRunAndDispatchesWork() {
        // The contrast to the re-delivery above: a decider that DOES emit ReviewRequested means a run is
        // genuinely starting, so claiming "reviewing" on the row is correct and FetchDiff goes out.
        var saga = sagaWith(policyMode(false), provider(List.of("alice")));
        saga.on(pr("acc-1", "alice"));
        assertEquals(List.of("bitbucket-cloud"), headerProviderTypes, "status/stage claimed for a real run");
        assertTrue(refreshedProviderTypes.isEmpty(), "no metadata-only refresh when a run starts");
        assertEquals(1, emitted.size());
        assertInstanceOf(ActionCommand.FetchDiff.class, emitted.get(0));
    }

    @Test
    void observeMode_registersHeaderButDoesNotStartTheReview() {
        var saga = sagaWith(policyMode(true), provider(List.of("alice")));
        saga.on(pr("acc-1", "alice"));
        // Observe registers the dashboard header but MUST NOT advance the aggregate —
        // otherwise a later active registration of the same commit stays stuck in DIFF.
        assertFalse(reviewRegistered, "observe mode emits no ReviewRequested (aggregate untouched)");
        assertEquals(List.of("bitbucket-cloud"), headerProviderTypes, "the dashboard header is still registered");
        assertTrue(emitted.isEmpty(), "observe mode emits no action commands");
        assertTrue(notes.contains("ReviewObserved"));
    }

    /**
     * A human's reply that plans a follow-up must flag the review as "answering" — a single
     * write that both bumps the dashboard's live feed (updated_at) and lets the UI show a
     * "responding" indicator while the bot works on an answer (fix #5).
     */
    @Test
    void authorReplied_setsAnsweringTrueWhenAFollowUpIsPlanned() {
        List<Boolean> answeringCalls = new ArrayList<>();
        String reviewId = "review::acme/web#412";
        var followUp = new ActionCommand.AnswerFollowUp(reviewId, new RepoRef("acme", "web"), 412L,
                new ThreadRef("t-1"), "c-1", "why is this a bug?", "scm-cred", "llm-cred", false, 1, 100L, 2.0);

        IntegrationSaga saga = new IntegrationSaga();
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
        saga.reviewProviders = new ReviewProviderResolver() {
            @Override
            public Optional<ScmProvider> resolveForReview(String reviewId) {
                return Optional.empty(); // no provider resolved -> isBotAuthored() is false, not a self-loop
            }
        };
        // Active: the reply path now consults the policy before planning a follow-up, so a fixture
        // that omits it no longer reaches the conversation at all. Left null this test NPE'd — which
        // is the honest signal that the gate genuinely covers this branch.
        saga.policy = policyMode(false);
        saga.conversation = new ConversationSaga() {
            @Override
            public Optional<ActionCommand> planFollowUp(AuthorReplied e) {
                return Optional.of(followUp);
            }
        };
        saga.projection = new ReviewProjection() {
            /** The archival gate reads this before the reply is handled at all; this review is live. */
            @Override
            public boolean archived(String reviewId) {
                return false;
            }

            @Override
            public void appendEvent(String reviewId, String lane, String type, String detail, String threadRef) {
            }

            @Override
            public void setAnswering(String reviewId, boolean answering) {
                answeringCalls.add(answering);
            }
        };

        saga.on(new AuthorReplied(new RepoRef("acme", "web"), 412L, reviewId, new ThreadRef("t-1"), "c-1",
                "why is this a bug?", Author.of("human-1", "alice", "Alice")));

        assertEquals(1, emitted.size(), "the planned AnswerFollowUp is still emitted");
        assertEquals(List.of(true), answeringCalls,
                "dispatching a follow-up flags the review as answering (and bumps the live dashboard)");
    }

    @Test
    void acceptedPullRequestEvent_setsPrStateOpen() {
        var saga = sagaWith(policyMode(false), provider(List.of("alice")));
        saga.on(pr("acc-1", "alice"));
        assertEquals(List.of("review::acme/web#412:OPEN"), prStateCalls,
                "an accepted PR event stamps the PR state OPEN on the registered review");
    }

    @Test
    void observedPullRequestEvent_stillSetsPrStateOpen() {
        // Observe-only still registers the dashboard header — the PR is genuinely open,
        // independent of whether the review pipeline runs (fix: PR-state badge).
        var saga = sagaWith(policyMode(true), provider(List.of("alice")));
        saga.on(pr("acc-1", "alice"));
        assertEquals(List.of("review::acme/web#412:OPEN"), prStateCalls);
    }

    @Test
    void skippedPullRequestEvent_neverSetsPrState() {
        var saga = sagaWith(policyMode(false), Optional.empty());
        saga.on(pr("acc-1", "alice"));
        assertTrue(prStateCalls.isEmpty(), "a skipped (non-registered) PR event must not stamp PR state");
    }

    @Test
    void pullRequestClosedMerged_setsPrStateMergedAndStillIssuesCancelReview() {
        var saga = sagaWith(policyMode(false), provider(List.of()));
        saga.on(new PullRequestClosed(new RepoRef("acme", "web"), 412L, CloseReason.MERGED));

        assertEquals(List.of("review::acme/web#412:MERGED"), prStateCalls);
        assertEquals(1, handledCommands.size(), "the existing cancel-on-close flow is unchanged");
        assertInstanceOf(RecordCommand.CancelReview.class, handledCommands.get(0));
    }

    @Test
    void pullRequestClosedDeclined_setsPrStateClosed() {
        var saga = sagaWith(policyMode(false), provider(List.of()));
        saga.on(new PullRequestClosed(new RepoRef("acme", "web"), 412L, CloseReason.DECLINED));

        assertEquals(List.of("review::acme/web#412:CLOSED"), prStateCalls);
        assertEquals(1, handledCommands.size(), "the existing cancel-on-close flow is unchanged");
        assertInstanceOf(RecordCommand.CancelReview.class, handledCommands.get(0));
    }

    /**
     * The badge alone left no record of when — or that — the PR ended: the review's own history
     * stopped at ReviewCompleted while the header read MERGED. Only the in-memory timeline saw it,
     * so a restart erased even that.
     */
    @Test
    void pullRequestClosedIsRecordedInTheReviewsOwnHistory() {
        var saga = sagaWith(policyMode(false), provider(List.of()));
        saga.on(new PullRequestClosed(new RepoRef("acme", "web"), 412L, CloseReason.MERGED));
        assertTrue(appendedEvents.contains("PullRequestClosed:merged"), appendedEvents.toString());
    }

    /** Declined says so, rather than reading as a merge that lost its badge. */
    @Test
    void aDeclinedPullRequestRecordsWhyItClosed() {
        var saga = sagaWith(policyMode(false), provider(List.of()));
        saga.on(new PullRequestClosed(new RepoRef("acme", "web"), 412L, CloseReason.DECLINED));
        assertTrue(appendedEvents.contains("PullRequestClosed:closed (declined)"), appendedEvents.toString());
    }
}
