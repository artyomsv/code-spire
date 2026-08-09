package dev.codespire.contract.command;

import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import dev.codespire.contract.llm.PromptTemplate;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.review.FindingVerdict;
import dev.codespire.contract.review.PriorFinding;
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
        @JsonSubTypes.Type(value = ActionCommand.AnswerFollowUp.class, name = "AnswerFollowUp"),
        @JsonSubTypes.Type(value = ActionCommand.NotifyTurnCap.class, name = "NotifyTurnCap"),
        @JsonSubTypes.Type(value = ActionCommand.NotifyArchived.class, name = "NotifyArchived")
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

    /**
     * {@code scmType} is the platform the review runs on, so a context provider can tell whether a
     * repo-relative reference (an issue number) belongs to its own host. Null when the review's
     * provider could not be resolved; a provider that needs it then contributes nothing.
     */
    /**
     * {@code repoRules} carries the repository's already-fetched {@code .codespire} rules. The
     * aggregator holds context-provider credentials only, never an SCM one, so the file is read at
     * diff-fetch — where an SCM client already exists — and passed through here.
     */
    record GatherContext(String reviewId, RepoRef repo, long prId, String commit,
                         Set<String> references,
                         String contextCredential,
                         ScmType scmType,
                         String repoRules) implements ActionCommand {
    }

    /**
     * providerOverride is set by the fallback saga on retry; worker re-fetches the diff by commit.
     * priorRun (ADR-019) is non-null only on a follow-up review of an already-posted PR — it
     * switches the worker into the reconcile + review two-call flow.
     */
    record GenerateReview(String reviewId, RepoRef repo, long prId, String commit, String contextRef,
                          int attempt, String providerOverride, String scmCredential,
                          String llmCredential, PriorRun priorRun,
                          PromptTemplate reviewPrompt, PromptTemplate reconcilePrompt) implements ActionCommand {

        // 10-arg: prior-run flow without prompt overrides (built-in defaults).
        public GenerateReview(String reviewId, RepoRef repo, long prId, String commit, String contextRef,
                              int attempt, String providerOverride, String scmCredential,
                              String llmCredential, PriorRun priorRun) {
            this(reviewId, repo, prId, commit, contextRef, attempt, providerOverride,
                    scmCredential, llmCredential, priorRun, null, null);
        }

        // 9-arg: first review, no prior run, built-in default prompts.
        public GenerateReview(String reviewId, RepoRef repo, long prId, String commit, String contextRef,
                              int attempt, String providerOverride, String scmCredential,
                              String llmCredential) {
            this(reviewId, repo, prId, commit, contextRef, attempt, providerOverride,
                    scmCredential, llmCredential, null, null, null);
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
    /**
     * {@code otherFindings} are the review's other open findings, each already owning its own thread.
     * The reply prompt lists them as off-limits: the diff shows every defect in the file, so without
     * this the model cannot tell which are already under discussion elsewhere and answers a narrow
     * question with a survey of the whole file. The REVIEW command has always carried the equivalent
     * exclusion list via {@link PriorRun}; this one did not.
     */
    record AnswerFollowUp(String reviewId, RepoRef repo, long prId, ThreadRef threadRef,
                          String triggeringCommentId, String question,
                          String scmCredential, String llmCredential, boolean mentioned,
                          int maxAttempts, long backoffBaseMs, double backoffFactor,
                          PromptTemplate followUpPrompt,
                          List<PriorFinding> otherFindings) implements ActionCommand {

        public AnswerFollowUp {
            otherFindings = otherFindings == null ? List.of() : List.copyOf(otherFindings);
        }

        // 13-arg: prompt override, no other-findings list (kept for call sites that have none).
        public AnswerFollowUp(String reviewId, RepoRef repo, long prId, ThreadRef threadRef,
                              String triggeringCommentId, String question,
                              String scmCredential, String llmCredential, boolean mentioned,
                              int maxAttempts, long backoffBaseMs, double backoffFactor,
                              PromptTemplate followUpPrompt) {
            this(reviewId, repo, prId, threadRef, triggeringCommentId, question, scmCredential,
                    llmCredential, mentioned, maxAttempts, backoffBaseMs, backoffFactor,
                    followUpPrompt, List.of());
        }

        // Without a prompt override — the worker uses the built-in default follow-up prompt.
        public AnswerFollowUp(String reviewId, RepoRef repo, long prId, ThreadRef threadRef,
                              String triggeringCommentId, String question,
                              String scmCredential, String llmCredential, boolean mentioned,
                              int maxAttempts, long backoffBaseMs, double backoffFactor) {
            this(reviewId, repo, prId, threadRef, triggeringCommentId, question,
                    scmCredential, llmCredential, mentioned, maxAttempts, backoffBaseMs, backoffFactor,
                    null, List.of());
        }
    }

    /**
     * The bot has used up its per-thread turns and is handing the thread back to the team — post a
     * one-off notice saying so, then stay quiet (spec §8).
     *
     * <p>Without this the cap was invisible outside the dashboard: the bot simply stopped replying,
     * which reads exactly like a lost webhook or a crash to the person waiting in the thread. Silence
     * is not a hand-off.
     *
     * <p>Carries no LLM credential — the notice is fixed text, so reaching the cap costs nothing and
     * always says the same thing. {@code threadRef} is the conversation ROOT, which is also the
     * idempotency slot: the notice posts once per thread however many further replies arrive.
     */
    record NotifyTurnCap(String reviewId, RepoRef repo, long prId, ThreadRef threadRef,
                         int turnCap, String scmCredential) implements ActionCommand {
    }

    /**
     * Tell a human that this review is archived and no further reviews will run for the pull request.
     *
     * <p>Carries no LLM credential — the notice is fixed text, so retiring a PR costs no tokens.
     * {@code threadRef} is null for a top-level PR comment and non-null to reply inside a thread, so
     * the notice appears where the event that triggered it arrived.
     */
    record NotifyArchived(String reviewId, RepoRef repo, long prId, ThreadRef threadRef,
                          String scmCredential) implements ActionCommand {
    }
}
