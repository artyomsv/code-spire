package dev.codespire.worker.pipeline;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.command.ActionCommand.GenerateReview;
import dev.codespire.contract.llm.Completion;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.ModelUsage;
import dev.codespire.contract.review.PriorFinding;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.scm.Diff;
import dev.codespire.contract.scm.ThreadTranscript;
import dev.codespire.diff.DiffRenderer;
import dev.codespire.llm.DiffBasis;
import dev.codespire.llm.ReconcilePrompt;
import dev.codespire.worker.adapters.WorkerLlmProvider;
import dev.codespire.worker.adapters.WorkerScmClients;

import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The API review's reconcile call (ADR-019): its own claim-guarded, paid LLM call judging each prior finding against
 * the incremental diff (or the full diff on a compare failure) plus its thread transcript. It runs before the review
 * call so a crash between the two replays cleanly. Moved out of {@link ReviewWorker} with the review input limit, which
 * made it size and clip its diff (PR 456).
 *
 * <p>The {@code RECONCILE_KEY} claim is checked FIRST, before any SCM call (ordering fix): a redelivery whose claim is
 * already posted must never re-fetch the compare diff — that live call's result can legitimately differ from the first
 * run's (a transient error, a blank result, different rename detection) and re-deriving fresh exclusions from it would
 * carry OLD paths while the persisted verdicts carry the FIRST run's remapped NEW paths, a mixed state. The
 * AlreadyPosted branch instead rebuilds the exclusion list from the PERSISTED verdicts via
 * {@link #exclusionsFromPersisted}. Only the Post branch — a genuinely fresh reconcile — fetches the compare diff and
 * remaps prior findings through its renames.
 */
final class ReconcileCall {

    /** Idempotency slot for the reconcile call (ADR-019) — same keyspace as the review call, its own claim. */
    static final String RECONCILE_KEY = "LLM:reconcile";

    /**
     * Persisted under RECONCILE_KEY so redelivery replays verdicts without a second spend. {@code truncated} and
     * {@code omittedFiles} carry a clipped reconcile diff to the posted note, also on a replay; a blob written before
     * they existed reads as not clipped. {@code clippedInputs} names what was cut ({@code diff},
     * {@code prior_findings}); a blob written before it reads as empty, which with {@code truncated} the result reads
     * as the worst case. The verdicts are stored after the unseen-file rule ran, so a replay never resolves what the
     * first run could not see.
     */
    record ReconcileOutcome(List<FindingVerdict> verdicts, ModelUsage usage, boolean truncated,
                            List<String> omittedFiles, List<String> clippedInputs) {

        ReconcileOutcome {
            omittedFiles = omittedFiles == null ? List.of() : List.copyOf(omittedFiles);
            clippedInputs = clippedInputs == null ? List.of() : List.copyOf(clippedInputs);
        }

        ReconcileOutcome(List<FindingVerdict> verdicts, ModelUsage usage) {
            this(verdicts, usage, false, List.of(), List.of());
        }
    }

    /**
     * The reconcile call's outcome (null when there is no prior run) plus the prior findings remapped through the
     * incremental diff's renames — computed ONCE and reused for the reconcile prompt, the review call's exclusion
     * list, and the verdicts (so a renamed file's finding carries its NEW path everywhere downstream, including on a
     * redelivered/replayed run).
     */
    record Reconciliation(List<PriorFinding> exclusions, ReconcileOutcome outcome) {
        static final Reconciliation NONE = new Reconciliation(List.of(), null);
    }

    /** The worker's collaborators the call uses. */
    record Ports(CommentIdempotencyStore idempotency, PromptLog promptLog, ObjectMapper mapper) {
    }

    /** What the command's reconcile reads: the clients built for it, the head's diff, and the model client. */
    record Sources(WorkerScmClients.Clients clients, Diff diff, WorkerLlmProvider.LlmClient llm) {
    }

    private final Ports ports;

    ReconcileCall(Ports ports) {
        this.ports = ports;
    }

    /** The reconcile of {@code command}'s prior run, replayed from its claim when one is already posted. */
    Reconciliation run(GenerateReview command, Sources sources) {
        PriorRun prior = command.priorRun();
        switch (ports.idempotency().claim(command.reviewId(), command.commit(), RECONCILE_KEY)) {
            case CommentIdempotencyStore.Claim.AlreadyPosted already -> {
                ReconcileOutcome outcome = readOutcome(already.postedRef());
                return new Reconciliation(exclusionsFromPersisted(outcome.verdicts(), prior.findings()), outcome);
            }
            case CommentIdempotencyStore.Claim.Post ignored -> { }
        }
        ReconcileSteps.ReviewAt at = new ReconcileSteps.ReviewAt(command.repo(), command.prId(), command.commit());
        ReconcileSteps.Basis basis = ReconcileSteps.basis(sources.clients().diff(), at, prior);
        ReconcilePrompt.Built built = ReconcilePrompt.build(input(sources, at, basis), command.reconcilePrompt(),
                sources.llm().limits());
        Completion completion = PaidCalls.complete(ports.promptLog().tap("reconcile", sources.llm().provider()),
                sources.llm().params(), new PaidCalls.Call(built.prompt(), built.size()));
        List<FindingVerdict> verdicts = ReconcileSteps.unchangedWhereUnseen(
                ReconcileSteps.judge(completion.text(), basis.remapped(), basis.incrementalDiff()), built.coverage());
        ReconcileOutcome outcome = new ReconcileOutcome(verdicts, completion.usage(), built.truncated(),
                built.omittedFiles(), List.copyOf(built.clippedInputs()));
        ports.idempotency().markPosted(command.reviewId(), command.commit(), RECONCILE_KEY, writeOutcome(outcome));
        return new Reconciliation(basis.remapped(), outcome);
    }

    /** The prompt's input: the remapped findings, their transcripts, and the compare diff or the full one. */
    private static ReconcilePrompt.ReconcileInput input(Sources sources, ReconcileSteps.ReviewAt at,
                                                        ReconcileSteps.Basis basis) {
        String incremental = basis.incrementalDiff();
        Map<String, ThreadTranscript> transcripts =
                ReconcileSteps.transcripts(sources.clients().comments(), at, basis.remapped());
        return incremental != null
                ? new ReconcilePrompt.ReconcileInput(basis.remapped(), transcripts, incremental, DiffBasis.INCREMENTAL)
                : new ReconcilePrompt.ReconcileInput(basis.remapped(), transcripts,
                        DiffRenderer.render(sources.diff().files()), DiffBasis.FULL);
    }

    /**
     * The review call's exclusion list, rebuilt from a PERSISTED reconcile outcome instead of a fresh compare fetch
     * (AlreadyPosted branch of {@link #run}) — mirrors the proven technique in
     * {@code ReviewProjection.stillOpenPriorFindings}. Each verdict is matched back to the ORIGINAL prior finding it
     * judged (threadRef when both are non-null, else path+line against the original — a verdict's own path/line
     * already reflect whatever rename the FIRST run followed) and rebuilt carrying the verdict's (fresher) path/line
     * with the matched finding's severity/message/threadRef. A verdict with no match is skipped; a prior finding with
     * no matching verdict at all is appended with its original fields (carried un-verdicted).
     */
    private static List<PriorFinding> exclusionsFromPersisted(List<FindingVerdict> verdicts,
                                                              List<PriorFinding> priorFindings) {
        boolean[] matched = new boolean[priorFindings.size()];
        List<PriorFinding> exclusions = new ArrayList<>();
        for (FindingVerdict v : verdicts) {
            int idx = matchPriorFindingIndex(v, priorFindings);
            if (idx >= 0) {
                matched[idx] = true;
                PriorFinding pf = priorFindings.get(idx);
                exclusions.add(new PriorFinding(v.path(), v.line(), pf.severity(), pf.message(), pf.threadRef()));
            }
        }
        for (int i = 0; i < priorFindings.size(); i++) {
            if (!matched[i]) {
                exclusions.add(priorFindings.get(i));
            }
        }
        return exclusions;
    }

    /** threadRef match first (both non-null), else path+line; -1 when neither matches. */
    private static int matchPriorFindingIndex(FindingVerdict v, List<PriorFinding> priorFindings) {
        for (int i = 0; i < priorFindings.size(); i++) {
            if (v.threadRef() != null && v.threadRef().equals(priorFindings.get(i).threadRef())) {
                return i;
            }
        }
        for (int i = 0; i < priorFindings.size(); i++) {
            PriorFinding pf = priorFindings.get(i);
            if (pf.path().equals(v.path()) && pf.line() == v.line()) {
                return i;
            }
        }
        return -1;
    }

    private String writeOutcome(ReconcileOutcome outcome) {
        try {
            return ports.mapper().writeValueAsString(outcome);
        } catch (JsonProcessingException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** @return the persisted reconcile outcome, or an empty/null one for a null or unreadable (legacy) blob. */
    private ReconcileOutcome readOutcome(String payload) {
        if (payload == null) {
            return new ReconcileOutcome(List.of(), null);
        }
        try {
            return ports.mapper().readValue(payload, ReconcileOutcome.class);
        } catch (JsonProcessingException e) {
            return new ReconcileOutcome(List.of(), null);
        }
    }
}
