package dev.codespire.worker.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand.FinishHarnessReview;
import dev.codespire.contract.command.ActionCommand.PrepareHarnessReview;
import dev.codespire.contract.event.IntegrationEvent;
import dev.codespire.contract.event.IntegrationEvent.HarnessReviewGenerated;
import dev.codespire.contract.event.IntegrationEvent.HarnessReviewPrepared;
import dev.codespire.contract.event.IntegrationEvent.HarnessReviewFailed;
import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.review.ContextItem;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.HarnessReviewInputs;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.review.ReviewRunBinding;
import dev.codespire.contract.scm.PullRequest;
import dev.codespire.contract.scm.ThreadTranscript;
import dev.codespire.llm.FindingsParser;
import dev.codespire.llm.HarnessReviewInputFiles;
import dev.codespire.worker.adapters.WorkerScmClients;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Map;

/**
 * Prepares and finishes a harness review (harness reviews, step 6). Prepare loads the context and, for a re-review,
 * numbers the prior open findings and persists that numbering; finish parses the agent's answer with the API path's
 * own parsers and backstops. The review worker never runs a harness (ADR-029): the run-worker does, between the two.
 */
@ApplicationScoped
public class HarnessReviewWorker {

    /** Every claim this worker writes starts with this, so {@code PostComments}' recovery can skip them all. */
    static final String CLAIM_PREFIX = "harness:";

    static final String PREPARE_PHASE = "harness-prepare";

    static final String FINISH_PHASE = "harness-finish";

    static final String OVER_CAP_SUMMARY = "The harness answer was larger than 256 KiB and was not read.";

    private static final Logger LOG = Logger.getLogger(HarnessReviewWorker.class);

    @Inject
    WorkerScmClients scm;

    @Inject
    CommentIdempotencyStore idempotency;

    @Inject
    ResultsEmitter results;

    @Inject
    AssembledContextReader contextReader;

    @Inject
    ObjectMapper mapper;

    /**
     * The prior findings exactly as the prepare numbered them, persisted under the prepare claim: verdict ids are
     * positions in this list, so finish judges against it and never a list recomputed later.
     */
    record PreparedPrior(String priorHead, boolean incremental, List<PriorFinding> numbered) {
    }

    /**
     * Fails like the API path: a gone commit is abandoned, anything else is a {@code HarnessReviewFailed} with the
     * worker's retry classification, so a transient read is retried on the same round. It names its round, as every
     * harness failure does: a forced re-run keeps the commit, and only the round keeps a late failure of round 1 from
     * failing round 2 (review of PR #370, code-quality/H-1).
     */
    public void prepare(PrepareHarnessReview command) {
        try {
            prepareOnce(command);
        } catch (RuntimeException e) {
            Throwable cause = WorkerFailures.unwrap(e);
            if (WorkerFailures.isCommitGone(cause)) {
                LOG.infof("Abandoning PrepareHarnessReview for %s: commit %s is gone (%s)",
                        command.reviewId(), command.commit(), cause.getMessage());
                return;
            }
            LOG.warnf(cause, "PrepareHarnessReview failed for %s", command.reviewId());
            results.emit(new HarnessReviewFailed(command.reviewId(), command.commit(), command.round(),
                    PREPARE_PHASE, cause.getMessage(), WorkerFailures.isRetryable(cause),
                    WorkerFailures.isCredentialRejected(cause)));
        }
    }

    /**
     * A transient fault propagates, so the record dead-letters with its paid answer and a replay finishes it at no
     * cost. Only a permanent fault fails the review, and terminally: a retry would meet the same run id and never
     * be answered. The failure names its round, as the prepare's does.
     */
    public void finish(FinishHarnessReview command) {
        try {
            finishOnce(command);
        } catch (RuntimeException e) {
            Throwable cause = WorkerFailures.unwrap(e);
            if (WorkerFailures.isRetryable(cause)) {
                LOG.warnf(cause, "FinishHarnessReview for %s met a transient fault; it is left for a replay",
                        command.reviewId());
                throw e;
            }
            LOG.warnf(cause, "FinishHarnessReview failed for %s", command.reviewId());
            results.emit(new HarnessReviewFailed(command.reviewId(), command.commit(), command.round(),
                    FINISH_PHASE, cause.getMessage(), false, false));
        }
    }

    // --- prepare ---

    private void prepareOnce(PrepareHarnessReview command) {
        WorkerScmClients.Clients clients = scm.forCommand(command);
        PullRequest pr = clients.diff().fetchPullRequest(command.repo(), command.prId());
        if (!Commits.matches(pr.headCommit(), command.commit())) {
            LOG.infof("Abandoning PrepareHarnessReview for %s: PR head moved past %s",
                    command.reviewId(), command.commit());
            return;
        }
        List<ContextItem> context = contextReader.load(command.contextRef());
        HarnessReviewInputs inputs = command.kind() == ReviewRunBinding.Kind.RECONCILE
                ? HarnessReviewInputFiles.reReview(pr, context, prior(command, clients))
                : HarnessReviewInputFiles.firstReview(pr, context);
        results.emit(new HarnessReviewPrepared(command.reviewId(), command.prId(), command.commit(),
                command.round(), command.kind(), inputs));
    }

    /** The numbered prior findings with their threads; transcripts are best-effort, so they are fetched each time. */
    private HarnessReviewInputFiles.Prior prior(PrepareHarnessReview command, WorkerScmClients.Clients clients) {
        ReconcileSteps.ReviewAt at = new ReconcileSteps.ReviewAt(command.repo(), command.prId(), command.commit());
        PreparedPrior prepared = numbering(command, clients, at);
        Map<String, ThreadTranscript> transcripts =
                ReconcileSteps.transcripts(clients.comments(), at, prepared.numbered());
        return new HarnessReviewInputFiles.Prior(prepared.priorHead(), prepared.numbered(), transcripts,
                prepared.incremental());
    }

    /**
     * The numbering is computed once per round and persisted before it is rendered: a redelivery reads it back, so
     * the ids the agent judges are the ids finish maps, whatever order a later prior list arrives in.
     */
    private PreparedPrior numbering(PrepareHarnessReview command, WorkerScmClients.Clients clients,
                                    ReconcileSteps.ReviewAt at) {
        String key = prepareKey(command.round());
        switch (idempotency.claim(command.reviewId(), command.commit(), key)) {
            case CommentIdempotencyStore.Claim.AlreadyPosted already -> {
                return readPreparedPrior(already.postedRef());
            }
            case CommentIdempotencyStore.Claim.Post ignored -> { }
        }
        ReconcileSteps.Basis basis = ReconcileSteps.basis(clients.diff(), at, command.priorRun());
        PreparedPrior prepared = new PreparedPrior(command.priorRun().headCommit(),
                basis.incrementalDiff() != null, basis.remapped());
        idempotency.markPosted(command.reviewId(), command.commit(), key, write(prepared));
        return prepared;
    }

    private PreparedPrior readPreparedPrior(String payload) {
        try {
            return mapper.readValue(payload, PreparedPrior.class);
        } catch (JsonProcessingException e) {
            // No cause: a parse error is an IOException, which would read as retryable, and a re-read fails alike.
            throw new IllegalStateException("the persisted numbering of the prior findings is unreadable");
        }
    }

    // --- finish ---

    /** Claim, then persist-then-emit, as the API path's paid call does: a redelivery re-emits the first outcome. */
    private void finishOnce(FinishHarnessReview command) {
        String key = finishKey(command.round());
        switch (idempotency.claim(command.reviewId(), command.commit(), key)) {
            case CommentIdempotencyStore.Claim.AlreadyPosted already -> {
                LOG.infof("Re-emitting HarnessReviewGenerated for %s from the persisted outcome", command.reviewId());
                results.emit(readGenerated(already.postedRef()));
                return;
            }
            case CommentIdempotencyStore.Claim.Post ignored -> { }
        }
        HarnessReviewGenerated generated = generated(command);
        idempotency.markPosted(command.reviewId(), command.commit(), key, writeEvent(generated));
        results.emit(generated);
    }

    private HarnessReviewGenerated generated(FinishHarnessReview command) {
        if (command.answerText() == null) {
            return outcome(command, ReviewResult.degraded(OVER_CAP_SUMMARY, command.usage()), List.of());
        }
        ReviewResult parsed = FindingsParser.parse(new Completion(command.answerText(), command.usage(), false));
        if (command.kind() != ReviewRunBinding.Kind.RECONCILE) {
            return outcome(command, parsed, List.of());
        }
        List<FindingVerdict> verdicts = verdicts(command);
        return outcome(command, ReconcileSteps.dropAnchorCollisions(parsed, verdicts), verdicts);
    }

    /**
     * The answer's verdicts against the numbering the prepare persisted. With no numbering no id can be trusted, so
     * none is read. The backstop gets a fresh compare only when the prepare had one: a head that was gone then gives
     * the full-diff semantics, and the agent's verdicts stand.
     */
    private List<FindingVerdict> verdicts(FinishHarnessReview command) {
        String persisted = idempotency.postedFor(command.reviewId(), command.commit())
                .get(prepareKey(command.round()));
        if (persisted == null) {
            LOG.warnf("No prepared numbering for %s round %d: the answer's verdicts are not read",
                    command.reviewId(), command.round());
            return List.of();
        }
        PreparedPrior prepared = readPreparedPrior(persisted);
        String incremental = prepared.incremental()
                ? ReconcileSteps.compareOrNull(scm.forCommand(command).diff(),
                        new ReconcileSteps.ReviewAt(command.repo(), command.prId(), command.commit()),
                        prepared.priorHead())
                : null;
        return ReconcileSteps.judge(command.answerText(), prepared.numbered(), incremental);
    }

    private static HarnessReviewGenerated outcome(FinishHarnessReview command, ReviewResult result,
                                                  List<FindingVerdict> verdicts) {
        return new HarnessReviewGenerated(command.reviewId(), command.prId(), command.commit(), command.round(),
                result, verdicts);
    }

    private HarnessReviewGenerated readGenerated(String payload) {
        try {
            if (mapper.readValue(payload, IntegrationEvent.class) instanceof HarnessReviewGenerated generated) {
                return generated;
            }
        } catch (JsonProcessingException e) {
            // No cause, for the reason readPreparedPrior gives.
            throw new IllegalStateException("the persisted harness review outcome is unreadable");
        }
        throw new IllegalStateException("the persisted harness review outcome is not a HarnessReviewGenerated");
    }

    // --- claims ---

    static String prepareKey(int round) {
        return CLAIM_PREFIX + "prepare#" + round;
    }

    static String finishKey(int round) {
        return CLAIM_PREFIX + "finish#" + round;
    }

    private String write(PreparedPrior prepared) {
        try {
            return mapper.writeValueAsString(prepared);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Written as an {@link IntegrationEvent}, so it carries its type and reads back as one. */
    private String writeEvent(HarnessReviewGenerated generated) {
        try {
            return mapper.writerFor(IntegrationEvent.class).writeValueAsString(generated);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }
}
