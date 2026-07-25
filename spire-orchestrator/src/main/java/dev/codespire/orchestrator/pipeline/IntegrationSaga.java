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
                lifecycle.handle(ReviewIds.reviewId(e.repo(), e.prId()),
                        new RecordCommand.CancelReview(e.reason().name()));
                projection.setPrState(ReviewIds.reviewId(e.repo(), e.prId()),
                        e.reason() == CloseReason.MERGED ? "MERGED" : "CLOSED");
            }
            case ManualCommandReceived e -> {
                if (isBotAuthored(reviewIdOf(e), e.author())) {
                    dropSelfLoop(reviewIdOf(e), "/" + e.command());
                } else if ("review".equals(e.command())) {
                    triggerManualReview(e);
                } else {
                    LOG.infof("Manual /%s command received — no handler", e.command());
                }
            }
            case AuthorReplied e -> {
                if (isBotAuthored(e.reviewId(), e.author())) {
                    dropSelfLoop(e.reviewId(), "reply");
                } else {
                    conversation.planFollowUp(e).ifPresent(cmd -> {
                        String author = e.author() == null ? "unknown" : e.author().username();
                        // The COMMAND's threadRef, not the event's: the saga normalized it to the
                        // conversation root, so every turn of one conversation is stored under the same
                        // ref — the review detail nests them all under the finding instead of spilling
                        // later turns into a bogus "General discussion" with an under-counted label.
                        projection.appendEvent(e.reviewId(), "integration", "AuthorReplied",
                                "@" + author + ": " + Previews.of(e.text()), cmd.threadRef().value());
                        // Flags "answering" AND bumps the live dashboard in one broadcast — replaces
                        // the plain touch() that used to sit here (fix #5, avoid double-broadcast).
                        projection.setAnswering(e.reviewId(), true);
                        commands.emit(cmd);
                    });
                }
            }
            default -> LOG.debugf("No integration reaction for %s", event.getClass().getSimpleName());
        }
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
        String commit = e.diffRefs().headSha();

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
        return e.author() == null ? "unknown" : e.author().username();
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
}
