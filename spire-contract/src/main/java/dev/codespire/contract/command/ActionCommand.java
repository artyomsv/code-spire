package dev.codespire.contract.command;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.PriorRun;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ThreadRef;

import java.util.List;
import java.util.Set;

/**
 * Action commands go to workers/adapters — they cause side effects and produce
 * integration result events (CONTRACT §5). Workers are idempotent by
 * causationId and pre-check run staleness before expensive/visible actions
 * (ADR-013).
 *
 * <p>The {@code type} discriminator is the cs.commands Kafka wire format
 * (CONTRACT §9/§11 — renames are breaking wire changes).
 */
@JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
@JsonSubTypes({
        // explicit names: nested records would otherwise get "Outer$Inner" ids
        @JsonSubTypes.Type(value = ActionCommand.FetchDiff.class, name = "FetchDiff"),
        @JsonSubTypes.Type(value = ActionCommand.GatherContext.class, name = "GatherContext"),
        @JsonSubTypes.Type(value = ActionCommand.GenerateReview.class, name = "GenerateReview"),
        @JsonSubTypes.Type(value = ActionCommand.PostComments.class, name = "PostComments"),
        @JsonSubTypes.Type(value = ActionCommand.AnswerFollowUp.class, name = "AnswerFollowUp")
})
public sealed interface ActionCommand {

    String reviewId();

    /**
     * Opaque, KEK-encrypted SCM credential the worker needs to execute this
     * command (ADR-015) — base64 Tink ciphertext of an {@link dev.codespire.contract.scm.ScmCredential},
     * resolved and packed by the orchestrator from the provider registry. Only
     * the credential-bearing commands carry it; {@code null} means "use the stub
     * SCM" (dev/observe). Never logged.
     */
    default String scmCredential() {
        return null;
    }

    /**
     * Opaque, KEK-encrypted LLM credential (ADR-018) — base64 Tink ciphertext of an
     * {@link dev.codespire.contract.llm.LlmCredential}, resolved and packed by the
     * orchestrator from the LLM provider registry (global default). Only
     * {@link GenerateReview} carries it; {@code null} means "use the stub LLM"
     * (dev/test). Never logged.
     */
    default String llmCredential() {
        return null;
    }

    /**
     * Opaque, KEK-encrypted context-source credentials — base64 Tink ciphertext of a
     * {@code List<}{@link dev.codespire.contract.context.ContextCredential}{@code >}, packed by
     * the orchestrator from EVERY enabled row of the context-provider registry (no single default; a
     * PR's references are matched against all of them). Only {@link GatherContext} carries it;
     * {@code null} means "no context source configured" — the worker still assembles (an empty/rules-only
     * context). Never logged.
     */
    default String contextCredential() {
        return null;
    }

    record FetchDiff(String reviewId, RepoRef repo, long prId, String commit,
                     String scmCredential) implements ActionCommand {
    }

    record GatherContext(String reviewId, RepoRef repo, long prId, String commit,
                         Set<String> ticketKeys, List<String> links,
                         String contextCredential) implements ActionCommand {
    }

    /**
     * providerOverride is set by the fallback saga on retry; worker re-fetches the diff by commit.
     * priorRun (ADR-019) is non-null only on a follow-up review of an already-posted PR — it
     * switches the worker into the reconcile + review two-call flow.
     */
    record GenerateReview(String reviewId, RepoRef repo, long prId, String commit, String contextRef,
                          int attempt, String providerOverride, String scmCredential,
                          String llmCredential, PriorRun priorRun) implements ActionCommand {

        public GenerateReview(String reviewId, RepoRef repo, long prId, String commit, String contextRef,
                              int attempt, String providerOverride, String scmCredential,
                              String llmCredential) {
            this(reviewId, repo, prId, commit, contextRef, attempt, providerOverride,
                    scmCredential, llmCredential, null);
        }
    }

    /**
     * Findings inline — same ReviewResult as ReviewGenerated (ADR-011). On a follow-up review the
     * verdicts drive thread replies/resolves (ADR-019) and priorSummaryRef is the summary comment
     * to update in place; both empty/null on a first review.
     */
    record PostComments(String reviewId, RepoRef repo, long prId, String commit,
                        ReviewResult findings, String scmCredential,
                        List<FindingVerdict> verdicts, String priorSummaryRef) implements ActionCommand {

        public PostComments {
            verdicts = verdicts == null ? List.of() : List.copyOf(verdicts);
        }

        public PostComments(String reviewId, RepoRef repo, long prId, String commit,
                            ReviewResult findings, String scmCredential) {
            this(reviewId, repo, prId, commit, findings, scmCredential, List.of(), null);
        }
    }

    /**
     * Worker fetches thread history from the SCM on demand — no blob (CONTRACT §5).
     * Carries the triggering comment id (for a per-question idempotency claim) and the brokered,
     * encrypted SCM + LLM credentials (ADR-015/ADR-018) — without them the worker would use the stub
     * adapters. The credential fields override the {@link ActionCommand} defaults.
     */
    record AnswerFollowUp(String reviewId, RepoRef repo, long prId, ThreadRef threadRef,
                          String triggeringCommentId, String question,
                          String scmCredential, String llmCredential, boolean mentioned,
                          int maxAttempts, long backoffBaseMs, double backoffFactor) implements ActionCommand {
    }
}
