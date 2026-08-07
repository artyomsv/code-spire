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
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.scm.Author;
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
        if ("review".equals(e.command())) {
            triggerManualReview(e);
        } else {
            LOG.infof("Manual /%s command received — no handler", e.command());
        }
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
