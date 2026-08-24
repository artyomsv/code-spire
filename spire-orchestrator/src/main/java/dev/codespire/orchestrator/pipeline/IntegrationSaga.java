package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.DomainEvent;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.AuthorReplied;
import dev.codespire.contract.event.IntegrationEvent.CloseReason;
import dev.codespire.contract.event.IntegrationEvent.ManualCommandReceived;
import dev.codespire.contract.event.IntegrationEvent.PullRequestClosed;
import dev.codespire.contract.event.IntegrationEvent.PullRequestEventReceived;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.CommentCommands;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.scm.Author;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadLocation;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.policy.ReviewPolicy;
import dev.codespire.orchestrator.provider.ProviderRegistry;
import dev.codespire.orchestrator.provider.ReviewProviderResolver;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.provider.WorkerCredentials;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.readmodel.ReviewThreadView;
import dev.codespire.orchestrator.view.TimelineBroadcaster;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

/**
 * Reacts to ingress events (cs.integration): translates them into Record
 * commands for the aggregate and, when a run starts, the first Action command.
 */
@ApplicationScoped
public class IntegrationSaga {

    private static final Logger LOG = Logger.getLogger(IntegrationSaga.class);

    @Inject
    ReviewLifecycleService lifecycle;

    @Inject
    TimelineBroadcaster timeline;

    @Inject
    CommandsEmitter commands;

    @Inject
    ReviewPolicy policy;

    @Inject
    ReviewProjection projection;

    @Inject
    ReviewThreadView threads;

    @Inject
    ProviderRegistry providers;

    @Inject
    ReviewProviderResolver reviewProviders;

    @Inject
    WorkerCredentials workerCredentials;

    @Inject
    ConversationSaga conversation;

    @Inject
    ReviewRerunService rerunService;

    @Incoming("integration-in")
    @Blocking // ordered (default): per-partition = per-review sequencing (CONTRACT §9, finding H3)
    public void on(IntegrationEvent event) {
        if (event == null) {
            return; // poison record already logged by the deserializer
        }
        // MDC (observability rule): the handler is @Blocking-synchronous, so
        // put/remove happen on the same worker thread.
        MDC.put("reviewId", reviewIdOf(event));
        try {
            handle(event);
        } finally {
            MDC.remove("reviewId");
        }
    }

    private void handle(IntegrationEvent event) {
        timeline.record("integration", event.getClass().getSimpleName(), reviewIdOf(event), "");
        // Ahead of the switch, so nothing an archived review owns is written on the way past — the
        // AuthorReplied branch below records a thread location before it consults any policy.
        String archivedId = archivedReviewIdOf(event);
        if (archivedId != null) {
            stopAtArchivedReview(archivedId, event);
            return;
        }
        switch (event) {
            case PullRequestEventReceived e -> onPullRequestEvent(e);
            case PullRequestClosed e -> {
                String reviewId = ReviewIds.reviewId(e.repo(), e.prId());
                boolean merged = e.reason() == CloseReason.MERGED;
                lifecycle.handle(reviewId, new RecordCommand.CancelReview(e.reason().name()));
                projection.setPrState(reviewId, merged ? "MERGED" : "CLOSED");
                // The badge alone left no record of WHEN the PR ended, or that it ended at all: the
                // event history stopped at ReviewCompleted while the header said MERGED. Only the
                // in-memory timeline saw it, so a restart erased even that.
                projection.appendEvent(reviewId, "integration", "PullRequestClosed",
                        merged ? "merged" : "closed (" + e.reason().name().toLowerCase(Locale.ROOT) + ")");
            }
            case ManualCommandReceived e -> onManualCommand(e);
            case AuthorReplied e -> {
                if (isBotAuthored(e.reviewId(), e.author())) {
                    dropSelfLoop(e.reviewId(), "reply");
                } else {
                    // Where the thread sits, recorded even when policy declines to answer: it is a fact
                    // about the thread, not about the reply, and it is what lets the UI file an inline
                    // conversation at its line instead of under "General discussion".
                    if (e.location() != null) {
                        threads.markThreadLocation(e.reviewId(), e.threadRef(),
                                e.location().path(), e.location().line());
                    }
                    conversation.planFollowUp(e).ifPresent(cmd -> {
                        String author = e.author() == null ? "unknown" : e.author().username();
                        // The COMMAND's threadRef, not the event's: the saga normalized it to the
                        // conversation root, so every turn of one conversation is stored under the same
                        // ref — the review detail nests them all under the finding instead of spilling
                        // later turns into a bogus "General discussion" with an under-counted label.
                        projection.appendEvent(e.reviewId(), "integration", "AuthorReplied",
                                "@" + author + ": " + Previews.of(e.text()), threadRefOf(cmd));
                        // Flags "answering" AND bumps the live dashboard in one broadcast — replaces
                        // the plain touch() that used to sit here (fix #5, avoid double-broadcast).
                        // Only for a real answer: the cap notice is fixed text with no LLM call, and
                        // flagging it would leave a "responding…" pill up for a reply that never comes.
                        if (cmd instanceof ActionCommand.AnswerFollowUp) {
                            projection.setAnswering(e.reviewId(), true);
                        }
                        commands.emit(cmd);
                    });
                }
            }
            default -> LOG.debugf("No integration reaction for %s", event.getClass().getSimpleName());
        }
    }

    /**
     * The id of the archived review this event targets, or null when it is live, unknown, or the event
     * cannot reach a review at all — {@link #reviewIdOf} answers {@code ""} for exactly those.
     */
    private String archivedReviewIdOf(IntegrationEvent event) {
        String reviewId = reviewIdOf(event);
        return !reviewId.isEmpty() && projection.archived(reviewId) ? reviewId : null;
    }

    /**
     * An archived pull request is retired: no push, {@code /command}, reply or close acts on it again.
     * Retirement is a SPEND boundary — an author pushing a commit must not silently re-bill an operator
     * who archived the review to be done with it.
     *
     * <p>The close is gated too, and it is the one that would otherwise go unnoticed: it is the event
     * that writes {@code pr_state}, so without this an archived review's badge would still move on the
     * next merge and the frozen-state promise would be false.
     *
     * <p>Always leaves a timeline note. A decision to stay silent that nobody can see is how the
     * conversation turn cap read as a lost webhook for a whole live run.
     */
    private void stopAtArchivedReview(String reviewId, IntegrationEvent event) {
        String what = event.getClass().getSimpleName();
        timeline.record("integration", "ArchivedReviewSkipped", reviewId,
                what + " ignored — this review is archived");
        LOG.infof("Ignoring %s on %s — the review is archived, so the pull request is retired",
                what, reviewId);
        noticeTriggerOf(event).flatMap(trigger -> archivedNotice(reviewId, trigger))
                .ifPresent(commands::emit);
    }

    /** What the notice needs from the event that triggered it: where to post, and who asked. */
    private record NoticeTrigger(RepoRef repo, long prId, ThreadRef threadRef, Author author) {
    }

    /**
     * The events that mean a human is waiting for an answer, and where the notice belongs: inside the
     * thread a reply arrived in, else the top-level PR comment.
     *
     * <p>{@code PullRequestClosed} is absent, and this is an allowlist rather than a "not a close" test
     * so no event added later inherits the notice by default. The notice fires once EVER: spending it
     * on a close would leave whoever later asks a real question with silence.
     */
    private static Optional<NoticeTrigger> noticeTriggerOf(IntegrationEvent event) {
        return switch (event) {
            case AuthorReplied e -> Optional.of(new NoticeTrigger(e.repo(), e.prId(),
                    e.topLevel() ? null : e.threadRef(), e.author()));
            case ManualCommandReceived e ->
                    Optional.of(new NoticeTrigger(e.repo(), e.prId(), null, e.author()));
            case PullRequestEventReceived e ->
                    Optional.of(new NoticeTrigger(e.repo(), e.prId(), null, e.author()));
            default -> Optional.empty();
        };
    }

    /**
     * The notice command, or empty when the gate must stay silent. Three silences, each its own reason:
     *
     * <p>The bot's own posted notice echoes back as an {@code AuthorReplied}, so without the self-loop
     * check it would re-enter this gate and emit a command on every echo, forever.
     *
     * <p>An author outside the provider's allowlist is refused for the same reason {@code /review} is
     * (see {@link #onManualCommand}): a notice that answered any commenter would partly reverse a gate
     * that exists to stop unlisted authors making the bot act.
     *
     * <p>With no resolvable provider there is no credential to broker, and a credential-less command
     * reaches the worker's stub sink — which would consume the once-ever claim while posting nothing
     * real, spending the notice permanently and invisibly.
     */
    private Optional<ActionCommand> archivedNotice(String reviewId, NoticeTrigger trigger) {
        if (isBotAuthored(reviewId, trigger.author())) {
            LOG.debugf("No archived notice on %s — the trigger is the bot's own comment", reviewId);
            return Optional.empty();
        }
        Optional<ScmProvider> provider = reviewProviders.resolveForReview(reviewId);
        if (provider.isEmpty()) {
            LOG.infof("No archived notice on %s — no provider resolves, so nothing could be posted "
                    + "and the once-ever notice stays available", reviewId);
            return Optional.empty();
        }
        if (!authorAllowed(provider.get().authors(), trigger.author())) {
            LOG.infof("No archived notice on %s — author @%s is not in the provider allowlist",
                    reviewId, username(trigger.author()));
            return Optional.empty();
        }
        return Optional.of(new ActionCommand.NotifyArchived(reviewId, trigger.repo(), trigger.prId(),
                trigger.threadRef(), workerCredentials.pack(provider.get())));
    }

    /**
     * A {@code /command} PR comment: our own bot's is dropped as a self-loop, then the SAME
     * per-provider allowlist that gates a PR event applies. It has to, because a command spends real
     * money — {@link ReviewRerunService} clears the worker's LLM idempotency claim on purpose, so the
     * model genuinely runs again — and nothing else bounds this path: {@code SPIRE_REVIEW_MAX_ATTEMPTS}
     * bounds auto-retry and the turn cap bounds follow-ups, neither covers a comment command. Without
     * the gate, anyone who can comment on the PR can bill the operator once per comment.
     *
     * <p>The gate sits ahead of the command switch rather than inside the {@code /review} branch, so a
     * future command cannot be added below it and arrive ungated — which is exactly how this one got in.
     *
     * <p><b>The refused author is not replied to</b>, unlike the turn cap (whose silence was a real
     * defect, because a missing ANSWER is indistinguishable from a lost webhook). An authorization
     * refusal is the opposite case: a reply confirms to a prober that the command exists and is wired,
     * and makes each probe cost an outbound comment. Timeline note plus a log line, as the PR-open path
     * records an unlisted author — and deliberately not a durable review-history row, which a prober
     * could otherwise grow without bound.
     */
    private void onManualCommand(ManualCommandReceived e) {
        String reviewId = reviewIdOf(e);
        if (isBotAuthored(reviewId, e.author())) {
            dropSelfLoop(reviewId, "/" + e.command());
            return;
        }
        // Resolved by the review's stored SCM type, the way the credential this command would broker
        // already is — a workspace name registered on two SCMs must check the right provider's list.
        // An unresolvable provider is left to the command itself, which refuses for want of a
        // credential (NotFoundException below); calling that an authorization failure would misreport it.
        if (!authorAllowed(allowlistFor(reviewId), e.author())) {
            timeline.record("integration", "ManualCommandSkipped", reviewId,
                    "author not in the provider's allowlist: @" + username(e.author()));
            LOG.infof("Skipping /%s on %s — author @%s not in the provider allowlist",
                    e.command(), reviewId, username(e.author()));
            return;
        }
        // Normalized because a switch over null throws where the old equals-test simply fell through
        // to "no handler": a hand-crafted record must not cost a consumer a trip through cs.dlq.
        String command = e.command() == null ? "" : e.command();
        switch (command) {
            case CommentCommands.REVIEW -> triggerManualReview(e);
            case CommentCommands.FINDING -> raiseConversationFinding(reviewId, e);
            default -> LOG.infof("Manual /%s command received — no handler", command);
        }
    }

    /**
     * A human filed a finding from a discussion ({@code /finding}). No LLM call and no spend gate —
     * nothing is asked of the model, because a person already decided.
     *
     * <p>Normalized to the conversation root first. On an SCM that threads by immediate parent, a
     * command typed in a reply carries THAT reply's id, and keying the finding — or the confirmation
     * — off it would split one conversation across two refs and hide the anchor, which lives on the
     * root. Every sibling path in this saga normalizes for the same reason.
     *
     * <p>The refusal here SPEAKS, unlike the authorization refusal in {@link #onManualCommand}: an
     * authorized author who used the command where it cannot work is told how to use it. Silence is
     * the answer to a prober, not to a colleague.
     */
    private void raiseConversationFinding(String reviewId, ManualCommandReceived e) {
        ThreadRef root = e.threadRef() == null ? null : threads.rootOf(reviewId, e.threadRef());
        // Only consulted when the event carried no location of its own — not every provider reports
        // one on every comment surface.
        ThreadLocation stored = root == null ? null : threads.locationOf(reviewId, root);
        switch (ConversationFindings.resolve(e, stored)) {
            case ConversationFindings.Refused r -> refuseConversationFinding(reviewId, e, r);
            case ConversationFindings.Filed f -> fileConversationFinding(reviewId, e, root, f);
        }
    }

    /** Timeline-only: the refusal text names what to do instead, and carries no finding text. */
    private void refuseConversationFinding(String reviewId, ManualCommandReceived e,
                                           ConversationFindings.Refused refusal) {
        timeline.record("integration", "refused:/" + CommentCommands.FINDING, reviewId,
                refusal.replyText());
        LOG.infof("Refused /%s on %s — no line to anchor to (thread=%s)", CommentCommands.FINDING,
                reviewId, e.threadRef() == null ? "none" : e.threadRef().value());
    }

    /**
     * Project, THEN append — the reverse of every other write in this saga, and the reversal is the
     * point.
     *
     * <p>This is the only path here where the read model is the finding's SOLE home: the domain event
     * deliberately carries anchor and severity but not the message, which may quote source code
     * (DATA-MODEL §5), so nothing can rebuild the finding from the log. Appending first meant a
     * transient fault on the projection write dead-lettered the message with the triggering comment
     * already in {@code raisedFindingComments} — the replay then found the aggregate saying "already
     * raised", returned here, and the human's finding was gone for good. Reordering makes the worst
     * case "no confirmation posted" instead of "finding destroyed", and costs nothing, because the
     * projection write is idempotent by construction ({@code dedupeByAnchor} collapses the anchor and
     * {@code mergeMessages} deduplicates constituents).
     *
     * <p>Which is why the aggregate is CONSULTED before the projection write and commanded after. A
     * completed round drops a resolved conversation finding from the baseline, so a late replay —
     * a DLQ replay is an operator action and can arrive hours later — would otherwise resurrect it.
     * The pre-check in {@link #canFileConversationFinding} is therefore the operative guard on a
     * redelivery, and it is safe to read-then-act on because everything is keyed by reviewId and
     * dispatch is per-partition ordered, so one consumer owns this review.
     *
     * <p>{@code handle}'s own empty answer is the BACKSTOP, and it is kept for the one window the
     * pre-check cannot cover: a consumer-group rebalance, where a revoked consumer's in-flight
     * message can overlap the new owner's replay of the same offset. Two threads can both pass the
     * pre-check there, and only the event store's optimistic concurrency and this empty answer keep
     * the confirmation from being posted twice.
     */
    private void fileConversationFinding(String reviewId, ManualCommandReceived e, ThreadRef root,
                                         ConversationFindings.Filed f) {
        if (!canFileConversationFinding(reviewId, e)) {
            return;
        }
        // The message goes to the encrypted read model and is never logged (DATA-MODEL §5).
        projection.addConversationFinding(reviewId, root.value(), f.path(), f.line(), f.severity(),
                f.message());
        List<DomainEvent> appended = lifecycle.handle(reviewId,
                new RecordCommand.RaiseConversationFinding(root, f.path(), f.line(), f.severity(),
                        f.message(), e.commentId()));
        if (appended.isEmpty()) {
            LOG.infof("Ignoring redelivered /%s on %s — its comment already raised a finding",
                    CommentCommands.FINDING, reviewId);
            return;
        }
        confirmFinding(reviewId, e, root, f);
    }

    /**
     * The two refusals that must happen before anything is written, both silent to the thread and
     * both visible on the timeline.
     *
     * <p>An unregistered PR is the dangerous one. Nothing else stops it: {@code archived} answers
     * false for a row that does not exist, and the provider resolves by workspace when the review has
     * no stored type, so the command sails through the archival gate and the allowlist. The read
     * model then drops the finding with a WARN while the aggregate keeps the comment id — so the bot
     * would confirm a finding that exists nowhere, and re-registering the PR could never file it,
     * because the redelivery is an idempotent no-op. Refused in {@code /review}'s own idiom, and
     * without advancing the aggregate, so registering the PR and re-running the command still works.
     */
    private boolean canFileConversationFinding(String reviewId, ManualCommandReceived e) {
        if (!projection.registered(reviewId)) {
            timeline.record("integration", "skipped:/" + CommentCommands.FINDING, reviewId,
                    "no registered review for this PR — open/update it first");
            LOG.infof("Skipping /%s on %s — no registered review for this PR",
                    CommentCommands.FINDING, reviewId);
            return false;
        }
        if (lifecycle.currentState(reviewId).raisedFindingComments().contains(e.commentId())) {
            LOG.infof("Ignoring redelivered /%s on %s — its comment already raised a finding",
                    CommentCommands.FINDING, reviewId);
            return false;
        }
        return true;
    }

    /**
     * Tell the thread the finding was filed, and at what. Fixed text, so it carries no LLM credential.
     *
     * <p>With no resolvable provider there is nothing to broker, and a credential-less command reaches
     * the worker's stub sink — it would consume the worker's claim while posting nothing real. The
     * finding itself is already filed and visible on the dashboard either way.
     */
    private void confirmFinding(String reviewId, ManualCommandReceived e, ThreadRef root,
                                ConversationFindings.Filed f) {
        Optional<ScmProvider> provider = reviewProviders.resolveForReview(reviewId);
        if (provider.isEmpty()) {
            LOG.infof("Filed a /%s on %s but posted no confirmation — no provider resolves, so there "
                    + "is no credential to post with", CommentCommands.FINDING, reviewId);
            return;
        }
        commands.emit(new ActionCommand.ConfirmFinding(reviewId, e.repo(), e.prId(), root,
                e.commentId(), f.severity(), f.path(), f.line(),
                workerCredentials.pack(provider.get())));
    }

    private List<String> allowlistFor(String reviewId) {
        return reviewProviders.resolveForReview(reviewId).map(ScmProvider::authors).orElse(List.of());
    }

    private void dropSelfLoop(String reviewId, String what) {
        timeline.record("integration", "SelfLoopDropped", reviewId, "bot-authored " + what + " ignored");
        LOG.debugf("Dropping bot-authored %s (self-loop guard) on %s", what, reviewId);
    }

    /** A /review PR comment forces a re-review of the PR's last-known commit (FR-12). */
    private void triggerManualReview(ManualCommandReceived e) {
        String reviewId = reviewIdOf(e);
        try {
            boolean started = rerunService.rerun(e.repo().workspace(), e.repo().slug(), e.prId());
            projection.appendEvent(reviewId, "integration", "ManualReview",
                    started ? "/review by @" + e.author().username() : "/review refused (already running)");
        } catch (jakarta.ws.rs.NotFoundException unknown) {
            timeline.record("integration", "skipped:/review", reviewId,
                    "no registered review for this PR — open/update it first");
        }
    }

    /**
     * Self-loop guard (ADR-013): true when a comment-derived event was authored by
     * the review's registered bot. Moved here from the gateway ingress — the bot
     * account id lives in the provider registry (whoami-resolved), which only the
     * orchestrator can read, so the internet-facing gateway holds no identity.
     * Resolves by the review's stored SCM type so a workspace name shared across
     * SCMs still checks the RIGHT bot (not the oldest provider on that workspace).
     */
    private boolean isBotAuthored(String reviewId, Author author) {
        if (author == null || author.providerUserId() == null || author.providerUserId().isBlank()) {
            return false;
        }
        return reviewProviders.resolveForReview(reviewId)
                .map(p -> author.providerUserId().equals(p.botAccountId()))
                .orElse(false);
    }

    private void onPullRequestEvent(PullRequestEventReceived e) {
        String reviewId = ReviewIds.reviewId(e.repo(), e.prId());
        String commit = e.headCommit();

        // Only PRs from a registered provider are reviewed. Resolve by (type,
        // workspace) when the event names its SCM — a GitHub org and a Bitbucket
        // workspace can share a name; fall back to workspace alone for events
        // serialized before providerType existed (or the dev simulator).
        Optional<ScmProvider> provider = e.providerType() == null
                ? providers.resolveByWorkspace(e.repo().workspace())
                : providers.resolve(e.providerType(), e.repo().workspace());
        if (provider.isEmpty()) {
            timeline.record("integration", "PullRequestSkipped", reviewId,
                    "no provider registered for workspace " + e.repo().workspace());
            LOG.infof("Skipping %s — no provider registered for workspace %s", reviewId, e.repo().workspace());
            return;
        }

        // Allowlist gate (per-provider): unlisted authors never get touched.
        if (!authorAllowed(provider.get().authors(), e.author())) {
            timeline.record("integration", "PullRequestSkipped", reviewId,
                    "author not in the provider's allowlist: @" + username(e));
            LOG.infof("Skipping %s — author @%s not in the provider allowlist", reviewId, username(e));
            return;
        }

        boolean observe = policy.observeOnly();
        // Ask the aggregate FIRST, so the read model only ever claims a run that is actually starting.
        // Observe-only must not advance the aggregate at all: emitting ReviewRequested here would lock
        // the review into REVIEWING, so a later active registration of the same commit would find it
        // "already requested" and never dispatch FetchDiff. The review must stay un-started so
        // activating it later runs a fresh pipeline.
        boolean started = !observe && lifecycle.handle(reviewId,
                        new RecordCommand.RequestReview(commit, e.action().name(), false))
                .stream().anyMatch(DomainEvent.ReviewRequested.class::isInstance);

        // Make the review visible on the dashboard whether or not any work runs — but a re-delivered
        // event for a commit the aggregate has already reviewed only refreshes the PR metadata. Claiming
        // "reviewing" there would overwrite the finished outcome, and because no run starts, nothing
        // would ever move it on again (a permanent "reviewing" with no command on the bus).
        if (observe) {
            projection.registerHeader(reviewId, e.repo(), e.prId(), e.title(), username(e), authorId(e),
                    e.sourceBranch(), e.targetBranch(), commit, e.htmlUrl(), provider.get().type(),
                    "observed", ReviewProjection.STAGE_RECEIVED);
        } else if (started) {
            projection.registerHeader(reviewId, e.repo(), e.prId(), e.title(), username(e), authorId(e),
                    e.sourceBranch(), e.targetBranch(), commit, e.htmlUrl(), provider.get().type(),
                    "reviewing", ReviewProjection.STAGE_DIFF);
        } else {
            projection.refreshHeader(reviewId, e.repo(), e.prId(), e.title(), username(e), authorId(e),
                    e.sourceBranch(), e.targetBranch(), commit, e.htmlUrl(), provider.get().type());
        }
        projection.appendEvent(reviewId, "integration", "PullRequestEventReceived",
                e.action().name().toLowerCase(Locale.ROOT) + " · head " + commit);
        projection.setPrState(reviewId, "OPEN");

        if (observe) {
            timeline.record("domain", "ReviewObserved", reviewId,
                    "observe-only: registered, no review run");
            projection.appendEvent(reviewId, "domain", "ReviewObserved", "observe-only: registered, no review run");
            projection.setNote(reviewId, "Observe-only mode — registered, no review run.");
            LOG.infof("Observe-only: registered %s, no review started", reviewId);
            return;
        }
        if (!started) {
            LOG.infof("Re-delivered event for %s at commit %s — already reviewed, no new run", reviewId, commit);
            return;
        }

        commands.emit(new ActionCommand.FetchDiff(reviewId, e.repo(), e.prId(), commit,
                workerCredentials.pack(provider.get())));
    }

    /** An empty provider allowlist reviews everyone; else match by account id or username. */
    private static boolean authorAllowed(List<String> allowlist, Author author) {
        if (allowlist == null || allowlist.isEmpty()) {
            return true;
        }
        if (author == null) {
            return false;
        }
        return allowlist.stream().anyMatch(a ->
                a.equalsIgnoreCase(author.providerUserId()) || a.equalsIgnoreCase(author.username()));
    }

    private static String username(PullRequestEventReceived e) {
        return username(e.author());
    }

    private static String username(Author author) {
        return author == null ? "unknown" : author.username();
    }

    private static String authorId(PullRequestEventReceived e) {
        return e.author() == null ? "" : e.author().providerUserId();
    }

    private String reviewIdOf(IntegrationEvent event) {
        return switch (event) {
            case PullRequestEventReceived e -> ReviewIds.reviewId(e.repo(), e.prId());
            case PullRequestClosed e -> ReviewIds.reviewId(e.repo(), e.prId());
            case ManualCommandReceived e -> ReviewIds.reviewId(e.repo(), e.prId());
            case AuthorReplied e -> e.reviewId();
            default -> "";
        };
    }

    /** The conversation root both reply commands carry, so the human's reply is filed under it either way. */
    private static String threadRefOf(ActionCommand command) {
        return switch (command) {
            case ActionCommand.AnswerFollowUp c -> c.threadRef().value();
            case ActionCommand.NotifyTurnCap c -> c.threadRef().value();
            default -> null;
        };
    }
}
