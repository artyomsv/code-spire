package dev.codespire.orchestrator.pipeline;

import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.ArchivedNotified;
import dev.codespire.contract.event.IntegrationEvent.CommentsPosted;
import dev.codespire.contract.event.IntegrationEvent.ContextAssembled;
import dev.codespire.contract.event.IntegrationEvent.DiffFetched;
import dev.codespire.contract.event.IntegrationEvent.FindingConfirmed;
import dev.codespire.contract.event.IntegrationEvent.FindingRefused;
import dev.codespire.contract.event.IntegrationEvent.FollowUpGenerated;
import dev.codespire.contract.event.IntegrationEvent.FollowUpPosted;
import dev.codespire.contract.event.IntegrationEvent.ReviewFailed;
import dev.codespire.contract.event.IntegrationEvent.ReviewGenerated;
import dev.codespire.contract.event.IntegrationEvent.TurnCapNotified;
import dev.codespire.contract.event.ReviewIds;
import dev.codespire.contract.command.ActionCommand;
import dev.codespire.contract.command.RecordCommand;
import dev.codespire.contract.lifecycle.ReviewState;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.scm.ThreadRef;
import dev.codespire.orchestrator.caps.CapRefusal;
import dev.codespire.orchestrator.lifecycle.ReviewLifecycleService;
import dev.codespire.orchestrator.llm.CallRefs;
import dev.codespire.orchestrator.llm.ChargeCall;
import dev.codespire.orchestrator.llm.ChargeKind;
import dev.codespire.orchestrator.llm.ChargeLine;
import dev.codespire.orchestrator.llm.DefaultLlm;
import dev.codespire.orchestrator.readmodel.ReviewProjection;
import dev.codespire.orchestrator.view.TimelineBroadcaster;
import io.smallrye.reactive.messaging.annotations.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.Set;

/**
 * Reacts to worker results (cs.results): issues the next Action command and
 * the matching Record command (CONTRACT §5).
 *
 * <p>Post-split home of the ADR-013 stale-run guard: the orchestrator owns the
 * aggregate, so results belonging to a superseded/cancelled run are dropped
 * HERE, before the next (possibly expensive) command is ever emitted. The
 * worker keeps only the PR-head re-check for its own visible actions.
 */
@ApplicationScoped
public class ResultSaga {

    private static final Logger LOG = Logger.getLogger(ResultSaga.class);

    @Inject
    ReviewLifecycleService lifecycle;

    @Inject
    TimelineBroadcaster timeline;

    @Inject
    CommandsEmitter commands;

    @Inject
    ReviewProjection projection;

    @Inject
    dev.codespire.orchestrator.readmodel.ReviewThreadView threads;

    @Inject
    dev.codespire.orchestrator.provider.WorkerCredentials workerCredentials;

    @Inject
    dev.codespire.orchestrator.provider.ReviewProviderResolver providers;

    @Inject
    dev.codespire.orchestrator.provider.ProviderRegistry providerRegistry;

    @Inject
    dev.codespire.orchestrator.llm.WorkerLlmCredentials workerLlmCredentials;

    @Inject
    dev.codespire.orchestrator.llm.LlmModelRegistry llmModels;

    /** Which run of a review a charge belongs to — a re-run spends again on the same commit. */
    @Inject
    dev.codespire.orchestrator.llm.ReviewRuns runs;

    @Inject
    dev.codespire.orchestrator.context.WorkerContextCredentials workerContextCredentials;

    @Inject
    dev.codespire.orchestrator.prompt.WorkerPromptTemplates promptTemplates;

    /** Max pipeline runs before a retryable failure fails terminally (C8) — read from the policy on each
     *  failure, so changing it in Settings takes effect without a restart. */
    @Inject
    dev.codespire.orchestrator.policy.ReviewPolicy reviewPolicy;

    /** The diff-size cap (Task 5) — read fresh from Settings on every DiffFetched, same posture as
     *  {@code reviewPolicy}, so a changed limit takes effect without a restart. Unset means unlimited. */
    @Inject
    dev.codespire.orchestrator.caps.CapPolicy capPolicy;

    /** The spend/call cap (Task 6), shared with {@link ConversationSaga}'s follow-up gate so the two
     *  paid-call sites cannot drift apart on what "over the cap" means. */
    @Inject
    dev.codespire.orchestrator.caps.SpendGate spendGate;

    int maxAttempts() {
        return reviewPolicy.maxAttempts();
    }

    @Incoming("results-in")
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
        timeline.record("result", event.getClass().getSimpleName(), reviewIdOf(event), "");
        switch (event) {
            // repo/prId are derived from the reviewId itself — no in-memory
            // registry, nothing lost on restart or scale-out (finding H2).
            case DiffFetched e -> ifCurrentRun(e.reviewId(), e.commit(), "DiffFetched", () -> {
                // Task 5: refuse before the context fan-out ever runs — per-issue API calls, a bounded
                // 20s wait and an encrypted blob write, all otherwise spent on a diff we will not review.
                // The diff statistics live only on THIS event (ContextAssembled carries none of them),
                // which is why the gate has to sit here rather than at the pre-spend check.
                CapRefusal diffSize = diffSizeDecision(e);
                if (diffSize.refused()) {
                    refuse(e.reviewId(), e.commit(), "FetchDiff", ReviewProjection.STAGE_DIFF, diffSize);
                    return;
                }
                projection.appendEvent(e.reviewId(), "result", "DiffFetched", e.changedFiles() + " files");
                projection.updateStage(e.reviewId(), ReviewProjection.STAGE_CONTEXT);
                // Pack every enabled context credential (Jira + Confluence) — null when none is configured,
                // in which case the worker assembles an empty context (the review still runs).
                String workspace = ReviewIds.parse(e.reviewId()).repo().workspace();
                String contextCred = workerContextCredentials.packAll(workspace).orElse(null);
                // Which platform this review runs on, from the same resolver the credential path and
                // the conversation saga use — one answer to "which SCM is this review on". Null when
                // unresolvable, which makes repo-relative context providers decline rather than guess.
                ScmType scmType = providers.resolveForReview(e.reviewId())
                        .flatMap(p -> ScmType.fromProviderType(p.type()))
                        .orElse(null);
                commands.emit(new ActionCommand.GatherContext(
                        e.reviewId(), ReviewIds.parse(e.reviewId()).repo(), e.prId(), e.commit(),
                        e.references() == null ? Set.of() : e.references(), contextCred, scmType,
                        // Already read at diff-fetch, from the PR's target branch — the aggregator
                        // holds no SCM credential and so cannot fetch it itself.
                        e.repoRules()));
            });
            case ContextAssembled e -> ifCurrentRun(e.reviewId(), e.commit(), "ContextAssembled", () -> {
                projection.appendEvent(e.reviewId(), "result", "ContextAssembled", "context assembled");
                projection.updateStage(e.reviewId(), ReviewProjection.STAGE_REVIEW);
                // GenerateReview also needs the LLM credential (ADR-018): resolve the global-default
                // provider and pack it encrypted. The resolve also answers whether the model can be
                // priced — the pre-spend guard, which arrives with the credential so it cannot be
                // skipped here.
                String workspace = ReviewIds.parse(e.reviewId()).repo().workspace();
                DefaultLlm llm = workerLlmCredentials.resolveDefault(workspace);
                if (!llm.isSpendable()) {
                    skipUnspendable(e.reviewId(), "GenerateReview", llm);
                    return;
                }
                // Task 6: the pre-spend gate, right beside priceability — both answer the same question
                // ("may this paid call be made"), so a reader should find every reason it was refused in
                // one place rather than half of them here and half in ConversationSaga.
                dev.codespire.orchestrator.caps.SpendGate.Decision spendDecision = spendGate.decide();
                if (spendDecision.refused()) {
                    refuse(e.reviewId(), e.commit(), "GenerateReview", ReviewProjection.STAGE_REVIEW,
                            spendDecision.refusal());
                    return;
                }
                PriorRun prior = projection.priorRunFor(e.reviewId()).orElse(null);
                dev.codespire.contract.scm.RepoRef repo = ReviewIds.parse(e.reviewId()).repo();
                dev.codespire.contract.llm.PromptTemplate reviewPrompt =
                        promptTemplates.forKind(dev.codespire.contract.llm.PromptKind.REVIEW, repo);
                dev.codespire.contract.llm.PromptTemplate reconcilePrompt =
                        promptTemplates.forKind(dev.codespire.contract.llm.PromptKind.RECONCILE, repo);
                emitWithCredential(e.reviewId(), "GenerateReview", scmCred -> new ActionCommand.GenerateReview(
                        e.reviewId(), repo, e.prId(), e.commit(),
                        e.contextRef(), 1, null, scmCred, llm.packed(), prior, reviewPrompt, reconcilePrompt));
            });
            case ReviewGenerated e -> {
                // OUTSIDE the staleness guard, and first — the same shape FollowUpGenerated has always
                // had. The worker's PR-head re-check runs BEFORE the paid call, so a commit pushed (or
                // the PR closed) while that call is in flight cannot un-spend it; charging inside
                // ifCurrentRun discarded a real charge, which stopped being an under-reported cost card
                // the moment the ledger became an enforcement input — push-then-push in a loop then buys
                // uncounted paid calls without bound. Safe against redelivery with no new mechanism:
                // recordCharges is INSERT ... ON CONFLICT (call_ref, token_type) DO NOTHING, and a
                // superseded run's call_ref differs from the new run's because CallRefs.reviewSlot keys
                // on the commit. Everything AFTER this still belongs to the current run only.
                chargeGeneratedCalls(e);
                ifCurrentRun(e.reviewId(), e.commit(), "ReviewGenerated", () -> onReviewGenerated(e));
            }
            case CommentsPosted e -> {
                projection.appendEvent(e.reviewId(), "result", "CommentsPosted", e.inline().size() + " inline comments");
                projection.updateStage(e.reviewId(), ReviewProjection.STAGE_POSTING);
                lifecycle.handle(e.reviewId(), new RecordCommand.RecordCommentsPosted(
                        e.commit(), e.summaryThreadRef(), e.inline().size()));
                // Scope A: every inline finding's comment id is a thread we own — a reply there engages the bot.
                // Record its (path, line) too so the review detail can nest that finding's conversation.
                // (The partial-retry reconstruction branch in the worker emits (anchorKey, 0); such a row simply
                //  won't match a finding loc and its thread falls to General discussion — never lost.)
                for (CommentsPosted.PostedInline inline : e.inline()) {
                    // Key ownership by the THREAD, not the comment: GitLab's discussion id differs from
                    // Ownership is keyed by the THREAD a reply arrives under; what that id is belongs to the
                    // adapter (a comment on GitHub/Bitbucket, a discussion on GitLab).
                    threads.markFindingThread(e.reviewId(), new ThreadRef(inline.threadRef()),
                            inline.path(), inline.line());
                }
                // Flag the summary thread so its replies classify as "general" (not a finding). is_ours unchanged.
                threads.markSummaryThread(e.reviewId(), new ThreadRef(e.summaryThreadRef()));
                // ADR-019: a reconciled thread's outcome lands on the timeline and, when the finding
                // is confirmed fixed, flags the thread resolved for the detail view.
                for (var outcome : e.threadOutcomes()) {
                    if (outcome.resolved()) {
                        threads.markResolved(e.reviewId(), new ThreadRef(outcome.threadRef()));
                    }
                    projection.appendEvent(e.reviewId(), "result",
                            outcome.resolved() ? "ThreadResolved" : "ThreadReplied",
                            outcome.status().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' '),
                            outcome.threadRef());
                }
                // Guarded by recordPosted's commit match: a superseded run's CommentsPosted
                // (possible only through the worker's head-re-check race) cannot stamp a
                // snapshot inconsistent with findings_json, which may already hold the
                // newer run's findings.
                projection.recordPosted(e.reviewId(), e.commit(), e.summaryThreadRef());
            }
            case FollowUpGenerated e -> {
                projection.appendEvent(e.reviewId(), "result", "FollowUpGenerated",
                        Previews.of(e.answerText()), e.threadRef().value());
                if (e.usage() != null) {
                    // Thread AND triggering comment: the worker claims on the pair, so a follow-up's
                    // ledger identity has to be the pair too or turn 2 is dropped as turn 1's redelivery.
                    charge(new ChargeRequest(e.reviewId(),
                            CallRefs.followUpSlot(e.threadRef().value(), e.triggeringCommentId()),
                            ChargeKind.FOLLOWUP), e.usage());
                }
                projection.touch(e.reviewId());
            }
            case FollowUpPosted e -> {
                // One conversation = one thread ref: count the turn on the ROOT, so a Bitbucket chain
                // (a new comment id per turn) accumulates toward the turn cap like a GitHub thread.
                ThreadRef root = threads.rootOf(e.reviewId(), e.threadRef());
                threads.bumpTurn(e.reviewId(), root, e.commentId());
                // The bot has now spoken here, so this IS a bot conversation: a further reply must engage
                // without needing another @-mention, which may be the only reason the thread started. Only
                // the answer comment used to be marked, so a thread the bot joined by mention declined the
                // very next reply. The summary thread is excluded deliberately — owning that would make
                // every later top-level PR comment engage the bot.
                if (!root.value().equals(projection.summaryRefOf(e.reviewId()).orElse(null))) {
                    threads.markOurThread(e.reviewId(), root);
                }
                // The bot's answer is itself a comment a human can reply to. On SCMs that thread by
                // immediate parent (Bitbucket), such a reply keys off the answer's id — so mark it owned
                // AND link it to the root, else a reply to the bot's answer isn't recognized as ours and
                // its turns land under a separate ref. Harmless on GitHub/GitLab, which key replies off
                // the stable thread root already.
                threads.markAnswerThread(e.reviewId(), new ThreadRef(e.commentId()), root);
                lifecycle.handle(e.reviewId(), new RecordCommand.RecordFollowUp(e.threadRef(), e.commentId()));
                // Normal-completion clear (fix #5) — also bumps the live dashboard, replacing the
                // plain touch() that used to sit here (avoid double-broadcast). A follow-up's
                // terminal failure never reaches this saga as an event (FollowUpWorker re-throws
                // straight to cs.dlq, no ReviewFailed), so that path relies instead on the next
                // review run's registerHeader reset to clear the flag.
                projection.setAnswering(e.reviewId(), false);
            }
            case TurnCapNotified e -> {
                ThreadRef root = threads.rootOf(e.reviewId(), e.threadRef());
                projection.appendEvent(e.reviewId(), "result", "TurnCapNotified",
                        "turn cap reached — handed back to the team", root.value());
                // No bumpTurn: the notice about running out of turns must not consume one. Link it to
                // the root anyway, so a human replying to the notice (on an SCM that threads by
                // immediate parent) is still recognized as this conversation rather than a new thread.
                threads.markAnswerThread(e.reviewId(), new ThreadRef(e.commentId()), root);
                projection.setAnswering(e.reviewId(), false);
            }
            case ArchivedNotified e -> {
                // Filed under the conversation root when the notice replied inside a thread, so it sits
                // with the rest of that conversation. threadRef is null whenever the notice went to the
                // top-level PR comment — the /review and PR-update paths, i.e. the common case — and
                // rootOf binds it into a statement, so a null would throw an NPE inside a try whose
                // catch (SQLException) cannot see it.
                String threadRef = e.threadRef() == null
                        ? null : threads.rootOf(e.reviewId(), e.threadRef()).value();
                projection.appendEvent(e.reviewId(), "result", "ArchivedNotified",
                        "told the pull request this review is archived", threadRef);
                // No bumpTurn: a notice that answers nothing must not consume one of the thread's turns.
            }
            case FindingConfirmed e -> {
                ThreadRef root = threads.rootOf(e.reviewId(), e.threadRef());
                projection.appendEvent(e.reviewId(), "result", "FindingConfirmed",
                        "confirmed a finding filed from this conversation", root.value());
                // No bumpTurn: filing a finding is not the bot taking a turn in the conversation,
                // exactly as TurnCapNotified is not. Link the posted confirmation to the root anyway,
                // so a human's reply to IT — on an SCM that threads by immediate parent — is
                // recognized as this conversation rather than treated as a fresh thread.
                threads.markAnswerThread(e.reviewId(), new ThreadRef(e.commentId()), root);
            }
            case FindingRefused e -> {
                ThreadRef root = threads.rootOf(e.reviewId(), e.threadRef());
                projection.appendEvent(e.reviewId(), "result", "FindingRefused",
                        "refused a /finding — no line to anchor to", root.value());
                // Same reasoning as FindingConfirmed: no turn consumed, but the reply is still linked
                // back to the root so a follow-up reply to it is recognized as this conversation.
                threads.markAnswerThread(e.reviewId(), new ThreadRef(e.commentId()), root);
            }
            case ReviewFailed e -> onReviewFailed(e);
            default -> LOG.debugf("No result reaction for %s", event.getClass().getSimpleName());
        }
    }

    /**
     * Everything a {@code ReviewGenerated} does that belongs to the CURRENT run: project the outcome,
     * reconcile against the prior run, and post. The charge is deliberately not here — it is written
     * before this runs, because spend is a fact about a call that already happened rather than a step
     * in the run's progress.
     */
    private void onReviewGenerated(ReviewGenerated e) {
        projection.appendEvent(e.reviewId(), "result", "ReviewGenerated",
                e.result().findings().size() + " findings");
        projection.recordOutcome(e.reviewId(), e.result(), ReviewProjection.STAGE_COMMENTS);
        java.util.Optional<PriorRun> prior = projection.priorRunFor(e.reviewId());
        String priorSummaryRef = prior.map(PriorRun::summaryThreadRef).orElse(null);
        if (!e.verdicts().isEmpty()) {
            projection.recordReconciliation(e.reviewId(), e.verdicts(),
                    prior.map(PriorRun::findings).orElse(List.of()));
        }
        // Carry-forward baseline for the NEXT follow-up (ADR-019 refinement): unconditional,
        // even with empty verdicts — a first review then stores just its own findings,
        // priming carry-forward for round 2 (recordPosted's COALESCE keeps first-review
        // posted_findings_json semantics unchanged since the two columns hold the same
        // findings in that case).
        projection.recordOpenFindings(e.reviewId(), e.result(), e.verdicts(),
                prior.map(PriorRun::findings).orElse(List.of()));
        if (e.result().truncated()) {
            projection.setNote(e.reviewId(), "Diff exceeded the review budget — partial review "
                    + "(changes beyond the token limit were not reviewed).");
        }
        lifecycle.handle(e.reviewId(), new RecordCommand.RecordReviewOutcome(
                e.commit(), e.result().findings().size(),
                Integer.toHexString(e.result().summary().hashCode())));
        emitWithCredential(e.reviewId(), "PostComments", cred -> new ActionCommand.PostComments(
                e.reviewId(), ReviewIds.parse(e.reviewId()).repo(), e.prId(), e.commit(), e.result(), cred,
                e.verdicts(), priorSummaryRef));
    }

    /**
     * Bounded auto-retry (C8). A retryable failure with budget left restarts the
     * pipeline from FetchDiff (same as a manual re-push, but automatic and
     * capped) — the LLM/comment idempotency stores make the re-run safe. When the
     * budget is exhausted, the provider is gone, or the failure is permanent, the
     * run fails terminally so the aggregate leaves REVIEWING instead of stalling.
     */
    private void onReviewFailed(ReviewFailed e) {
        projection.appendEvent(e.reviewId(), "result", "ReviewFailed", "failed at " + e.phase());
        ReviewIds.Parsed parsed = ReviewIds.parse(e.reviewId());
        int attempt = projection.currentAttempt(e.reviewId());
        // Read once per failure so the decision, the log and the operator-facing note cannot disagree
        // if Settings changes the budget mid-flight.
        int budget = maxAttempts();

        if (e.retryable() && attempt < budget) {
            // The credential is checked NOW rather than at dispatch: a provider that has been removed
            // makes the retry pointless, and failing here keeps the terminal reason accurate.
            if (workerCredentials.packForReview(e.reviewId()).isPresent()) {
                int next = attempt + 1;
                // Backing off has to outlive this method: the consumer is ordered per partition, so
                // sleeping here would stall every other review on it. The due time is persisted and a
                // sweeper dispatches it (ReviewRetryScheduler) — which also survives a restart.
                java.time.Duration delay = reviewPolicy.retryDelay(next);
                LOG.warnf("Review %s hit a retryable failure at %s — auto-retry %d/%d in %ds (error: %s)",
                        e.reviewId(), e.phase(), next, budget, delay.toSeconds(), e.error());
                projection.scheduleRetry(e.reviewId(), next,
                        "Transient failure at " + e.phase() + " — retrying in " + humanDelay(delay)
                                + " (attempt " + next + "/" + budget + ").",
                        java.time.Instant.now().plus(delay));
                timeline.record("result", "retry:" + e.phase(), e.reviewId(),
                        "retryable failure — auto-retry " + next + "/" + budget + " in " + humanDelay(delay));
                // The retry is scheduled above — this review's own lifecycle handling is done. Recording
                // the credential fact runs last, so a persistence failure here cannot swallow the retry.
                if (e.credentialRejected()) {
                    markCredentialRejected(e.reviewId());
                }
                return;
            }
        }

        // Terminal: budget exhausted, provider gone, or a permanent failure. Drop any retry that was
        // already scheduled, so a run that ends here is not resurrected by the sweeper.
        projection.clearScheduledRetry(e.reviewId());
        String note = terminalNote(e, attempt, budget);
        LOG.errorf("Review %s failed terminally at %s (attempt %d/%d, retryable=%s, error: %s) — %s",
                e.reviewId(), e.phase(), attempt, budget, e.retryable(), e.error(), note);
        // Status, stage, note and the provider/worker error the UI shows, in one status-guarded write:
        // a refusal is a read-model refinement of the same terminal state, and this saga is the second
        // writer of it (DomainEventSink is the first). Writing unguarded relabelled a policy decision as
        // an infrastructure failure on any replay of this event.
        projection.projectTerminalFailure(e.reviewId(), phaseStage(e.phase()), note, e.error());
        // Force non-retryable so the decider yields ReviewFailedTerminally and the run leaves REVIEWING.
        lifecycle.handle(e.reviewId(), new RecordCommand.RecordFailure(e.commit(), e.phase(), false));
        // Recording the credential fact runs last, so a persistence failure here cannot swallow the
        // review's own terminal handling above.
        if (e.credentialRejected()) {
            markCredentialRejected(e.reviewId());
        }
    }

    /**
     * End a review because policy refused to spend on it. Mirrors {@link #onReviewFailed}'s terminal
     * shape so the aggregate leaves REVIEWING — without that the review sits there until REVIEW_STUCK
     * fires, blaming a webhook or a worker for what was a deliberate decision, and ADR-024's archive
     * guard refuses to clear anything still 'reviewing'.
     *
     * <p>Status is 'refused', not 'failed': the archive guard, the attention queries and the reviews
     * list all key on status, so filing a policy decision as an infrastructure failure would put it in
     * the same bucket as a genuine outage. This project split pr_state out of status for the same
     * reason — two different facts cannot share one badge.
     *
     * <p>{@code setError} is deliberately NOT called. There is no provider or worker error here, and
     * populating it would make the detail page show an infrastructure error for a policy decision.
     */
    private void refuse(String reviewId, String commit, String phase, int stage, CapRefusal refusal) {
        projection.clearScheduledRetry(reviewId);
        timeline.record("result", "refused:" + phase, reviewId, refusal.detail());
        projection.updateStatus(reviewId, "refused", stage);
        projection.setNote(reviewId, refusal.note());
        // logDetail, not detail: the log is the one surface no viewer reads, so it is where the
        // configured limit belongs beside the measured figure.
        LOG.warnf("Refused %s for %s — %s", phase, reviewId, refusal.logDetail());
        // retryable=false, so the decider yields a terminal state and the run leaves REVIEWING.
        lifecycle.handle(reviewId, new RecordCommand.RecordFailure(commit, phase, false));
    }

    /** Package-private passthrough: the gates that call {@link #refuse} arrive in later tasks, and
     *  widening the method itself would make a saga-internal transition part of the bean's API. */
    void refuseForTest(String reviewId, String commit, String phase, int stage, CapRefusal refusal) {
        refuse(reviewId, commit, phase, stage, refusal);
    }

    /**
     * Whether this diff exceeds either configured limit. Both figures are always named together
     * ({@link CapRefusal#diffTooLarge}), independent of which one tripped it, so the operator reads one
     * sentence rather than guessing which axis was the actual cause.
     *
     * <p>An unset limit never compares — {@code OptionalInt}/{@code OptionalLong} being empty is
     * itself the "unlimited" case, not a zero to compare against.
     *
     * <p><b>Refuses on {@code >}, where the spend gate refuses on {@code >=}, deliberately.</b> A size
     * limit is a ceiling a diff may reach: a 100-file limit says a 100-file diff is reviewable. A budget
     * is exhausted once it has been consumed, so reaching a 100-call cap means the 101st call must not
     * happen. Stated on both sides because {@code SpendGate} exists precisely because two money
     * comparisons are free to drift, and an undocumented difference between the gates is
     * indistinguishable from exactly that drift. Both boundaries are asserted ({@code DiffSizeGateTest},
     * {@code SpendGateTest}).
     */
    private CapRefusal diffSizeDecision(DiffFetched e) {
        OptionalInt maxFiles = capPolicy.maxChangedFiles();
        if (maxFiles.isPresent() && e.changedFiles() > maxFiles.getAsInt()) {
            return CapRefusal.diffTooLarge(e.changedFiles(), e.sizeBytes());
        }
        OptionalLong maxBytes = capPolicy.maxDiffBytes();
        if (maxBytes.isPresent() && e.sizeBytes() > maxBytes.getAsLong()) {
            return CapRefusal.diffTooLarge(e.changedFiles(), e.sizeBytes());
        }
        return CapRefusal.allow();
    }

    /**
     * Turn one review's 401 into a standing fact about its provider, so the operator sees a
     * credential to rotate instead of a review that failed for no visible reason.
     *
     * <p>The stored detail is a fixed string, never the provider's response body: a 401 body is a
     * plausible place for a token to be echoed back, and this text is persisted and rendered.
     */
    private void markCredentialRejected(String reviewId) {
        providers.resolveForReview(reviewId).ifPresentOrElse(
                provider -> providerRegistry.recordCheck(provider.id(), false,
                        "Authentication rejected (HTTP 401)"),
                () -> LOG.warnf("Credential rejected for review %s but its provider could not be "
                        + "resolved — not recording", reviewId));
    }

    /** "8s" / "2m 30s" — the note is read by an operator wondering why nothing is happening yet. */
    static String humanDelay(java.time.Duration delay) {
        long seconds = Math.max(0, delay.toSeconds());
        if (seconds < 60) {
            return seconds + "s";
        }
        long remainder = seconds % 60;
        return remainder == 0 ? (seconds / 60) + "m" : (seconds / 60) + "m " + remainder + "s";
    }

    private String terminalNote(ReviewFailed e, int attempt, int maxAttempts) {
        if (e.retryable() && attempt >= maxAttempts) {
            return "Failed at " + e.phase() + " after " + attempt + " attempts (retry budget exhausted).";
        }
        if (e.retryable()) {
            return "Failed at " + e.phase() + " — provider unavailable, cannot retry.";
        }
        return "Failed terminally at " + e.phase() + ".";
    }

    /**
     * A paid command that will not be issued, said out loud: timeline note, dashboard note and a WARN.
     * The dashboard note is what turns "nothing happened" into "here is what to fix" — the same shape
     * as {@link #emitWithCredential}'s missing-provider skip.
     */
    private void skipUnspendable(String reviewId, String phase, DefaultLlm llm) {
        timeline.record("result", "skipped:" + phase, reviewId, llm.detail());
        projection.setNote(reviewId, llm.note());
        LOG.warnf("Skipping %s for %s — %s", phase, reviewId, llm.detail());
    }

    /**
     * Resolve the workspace's provider credential (ADR-015) and emit the built
     * command. If the provider was disabled/removed mid-review the credential is
     * absent, so the command is skipped with a visible note rather than emitted
     * uncredentialed.
     */
    private void emitWithCredential(String reviewId, String phase,
                                    java.util.function.Function<String, ActionCommand> build) {
        String workspace = ReviewIds.parse(reviewId).repo().workspace();
        java.util.Optional<String> cred = workerCredentials.packForReview(reviewId);
        if (cred.isEmpty()) {
            timeline.record("result", "skipped:" + phase, reviewId,
                    "provider unavailable for workspace " + workspace + " — cannot issue " + phase);
            projection.setNote(reviewId,
                    "Provider for " + workspace + " is disabled or removed — " + phase + " not issued.");
            LOG.warnf("Skipping %s for %s — no enabled provider for workspace %s", phase, reviewId, workspace);
            return;
        }
        commands.emit(build.apply(cred.get()));
    }

    /**
     * Which paid call this is. {@code slot} is {@link CallRefs#reviewSlot} for a review or reconcile
     * call and {@link CallRefs#followUpSlot} for a follow-up — between them these carry the worker's
     * whole idempotency identity, so the ref derived from one is stable across redelivery and distinct
     * across genuinely separate calls.
     */
    private record ChargeRequest(String reviewId, String slot, ChargeKind kind) {
    }

    /**
     * The paid calls one {@code ReviewGenerated} stands for: the review call, and the ADR-019 reconcile
     * call when a follow-up commit triggered one. Both share the run-discriminated slot, since both
     * were made for this run of this commit.
     */
    private void chargeGeneratedCalls(ReviewGenerated e) {
        ModelUsage reviewUsage = e.result().usage();
        if (reviewUsage == null) {
            // Guarded like the two sibling sites below, which is what this one was missing:
            // charge() dereferences the usage for the model name, so a usage-less result
            // NPE'd the WHOLE handler into cs.dlq — findings, verdicts and PostComments with
            // it. Not charged either: llm_charge.model is NOT NULL and there is no model to
            // name, and a blank one would feed latestModelFor and render the review's real
            // spend as "no charges recorded yet".
            //
            // WARN rather than silence because this cannot happen by design:
            // TokenUsageMapper always answers a ModelUsage (a null input yields an
            // unreconciled TOTAL), so a null here means the worker emitted a result with no
            // accounting at all — a defect upstream, not a case to absorb. Recording nothing
            // quietly would hide it behind an empty cost card.
            LOG.warnf("No usage on ReviewGenerated for %s — no charge recorded; the worker"
                    + " emitted a result without token accounting", e.reviewId());
        }
        if (reviewUsage == null && e.reconcileUsage() == null) {
            return; // nothing was paid for, so do not spend a query resolving which run this is
        }
        String slot = CallRefs.reviewSlot(e.commit(), runs.currentRun(e.reviewId()));
        if (reviewUsage != null) {
            charge(new ChargeRequest(e.reviewId(), slot, ChargeKind.REVIEW), reviewUsage);
        }
        if (e.reconcileUsage() != null) {
            charge(new ChargeRequest(e.reviewId(), slot, ChargeKind.RECONCILE), e.reconcileUsage());
        }
    }

    /** Price a call and append its lines. */
    private void charge(ChargeRequest request, ModelUsage usage) {
        List<ChargeLine> lines = llmModels.priceCall(usage.model(), usage);
        projection.recordCharges(new ChargeCall(request.reviewId(),
                CallRefs.of(request.reviewId(), request.slot(), request.kind()),
                request.kind(), usage.model(), lines));
    }

    /** ADR-013: a superseded/cancelled run's results never trigger the next command. */
    private void ifCurrentRun(String reviewId, String commit, String phase, Runnable action) {
        ReviewState state = lifecycle.currentState(reviewId);
        if (state.isReviewing() && Objects.equals(commit, state.currentCommit())) {
            action.run();
        } else {
            timeline.record("result", "dropped:" + phase, reviewId, "stale run — superseded or cancelled");
        }
    }

    /** Map a worker failure phase to the pipeline step it stalled on. */
    private static int phaseStage(String phase) {
        String p = phase == null ? "" : phase.toLowerCase(java.util.Locale.ROOT);
        if (p.contains("diff")) {
            return ReviewProjection.STAGE_DIFF;
        }
        if (p.contains("context")) {
            return ReviewProjection.STAGE_CONTEXT;
        }
        if (p.contains("comment") || p.contains("post")) {
            return ReviewProjection.STAGE_COMMENTS;
        }
        return ReviewProjection.STAGE_REVIEW; // generate / llm / unknown
    }

    private String reviewIdOf(IntegrationEvent event) {
        return switch (event) {
            case DiffFetched e -> e.reviewId();
            case ContextAssembled e -> e.reviewId();
            case ReviewGenerated e -> e.reviewId();
            case CommentsPosted e -> e.reviewId();
            case FollowUpGenerated e -> e.reviewId();
            case FollowUpPosted e -> e.reviewId();
            case TurnCapNotified e -> e.reviewId();
            case ArchivedNotified e -> e.reviewId();
            case FindingConfirmed e -> e.reviewId();
            case FindingRefused e -> e.reviewId();
            case ReviewFailed e -> e.reviewId();
            default -> "";
        };
    }
}
