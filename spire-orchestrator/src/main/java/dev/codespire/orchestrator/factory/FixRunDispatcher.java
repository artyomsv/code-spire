package dev.codespire.orchestrator.factory;

import dev.codespire.contract.command.RunCommand;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.orchestrator.caps.SpendGate;
import dev.codespire.orchestrator.llm.LlmModelPricer;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.readmodel.FindingProjection;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.List;
import java.util.Optional;

/**
 * Turns an accepted {@code /fix} into a dispatched run (FR-F27, ADR-040).
 *
 * <p><b>Its own class rather than more of {@code IntegrationSaga}</b>, which is already past this
 * project's size guideline and has a debt entry saying so. The saga decides whether the command is
 * ADMISSIBLE — who asked, is the review registered, does the thread name an open finding — and this
 * decides whether it is DISPATCHABLE, then does it. The split follows the one {@link FixDispatch}
 * already made: that answers where a fix may push, this assembles and sends the run.
 *
 * <p><b>Every refusal carries a reason, and their ORDER is a decision rather than an accident.</b>
 * An author gets one message, so it should be the one they can act on. Durable operator facts are
 * reported ahead of transient ones — the argument {@link FixDispatch} already makes for its own
 * caps: telling someone their pull request is merged, when a spend cap would have refused them
 * anyway, sends them to reopen it for nothing.
 *
 * <p><b>Nothing is written and nothing is packed until every gate has passed.</b> A refused run must
 * leave no row, no claim and no rotation slot consumed — which is why the credential is selected
 * LAST of the checks: selecting stamps {@code last_used_at}, and that is a write.
 */
@ApplicationScoped
public class FixRunDispatcher {

    private static final Logger LOG = Logger.getLogger(FixRunDispatcher.class);

    /**
     * A fix names no extra protected paths, and that is not an oversight.
     *
     * <p>The push gate judges the diff against its own floor and is the authority; this list is the
     * per-run ADDITION to it, which the REST endpoint also leaves empty because nothing configures
     * one. Naming paths here would read as the protection and be only a part of it.
     */
    private static final List<String> NO_EXTRA_PROTECTED_PATHS = List.of();

    @Inject
    FixDispatch plans;

    @Inject
    FactoryRunProjection runs;

    @Inject
    FindingProjection findings;

    @Inject
    MachineAccounts machineAccounts;

    @Inject
    HarnessCredentialPool pool;

    @Inject
    RunCredentials credentials;

    @Inject
    FactoryConfig config;

    @Inject
    LlmModelPricer pricer;

    @Inject
    SpendGate spendGate;

    @Inject
    RunLaunch launch;

    /** What became of a {@code /fix} the saga had already accepted. */
    public sealed interface Result permits Dispatched, Refused {
    }

    /** @param runId the address the run answers on, so the durable note can name it */
    public record Dispatched(String runId) implements Result {
    }

    /** Refused, in words the author can act on. */
    public record Refused(String why) implements Result {
    }

    /**
     * @param threadRef the CONVERSATION ROOT, already normalised by the saga — the same value the
     *     finding was looked up by, so both caps count on the key the target was found on
     * @param commentId the comment that typed the command: the idempotency claim, and not optional
     */
    public Result dispatch(String reviewId, RepoRef repo, String threadRef, String commentId,
                           FindingProjection.TargetFinding finding) {
        // FIRST, because it is the only gate that can answer "this already happened" -- and every
        // gate below it is a reason to refuse a NEW request, which a redelivery is not. A repeat
        // delivery told about a spend cap would look like a lost request rather than a finished one.
        Refused duplicate = refuseIfAlreadyBought(reviewId, commentId);
        if (duplicate != null) {
            return duplicate;
        }
        SpendGate.Decision cap = spendGate.decide();
        if (cap.refused()) {
            return new Refused(cap.refusal().detail() + " — capacity returns as older usage ages out, "
                    + "or an operator can raise the cap in Settings → General");
        }
        if (cap.ledgerUnreadable()) {
            // FAIL OPEN, like every other enforcement site, and that is the project posture rather
            // than an oversight here: refusing on a failed READ turns an outage into something that
            // reads as policy, and SpendGate's own javadoc argues that case. The operator is told
            // through the attention row, which reaches the same verdict from the same call.
            //
            // What is different on THIS arm is the size of what proceeds. A review call is one LLM
            // call; a fix run is a container with a wall clock and a push credential, startable
            // again by any allowlisted commenter. So the arm that is about to spend the most says
            // so in its own log rather than relying on a panel nobody is looking at yet.
            LOG.warnf("Dispatching /fix on %s with the spend cap NOT enforcing: the usage ledger "
                    + "could not be read, so this run is allowed on a figure nobody has seen", reviewId);
        }
        // Once: it consults both fix caps and reads the review row, and calling it twice would read
        // a table twice to answer one question.
        FixDispatch.Plan plan = plans.plan(reviewId, threadRef, repo);
        if (plan instanceof FixDispatch.Refused refused) {
            // Its wording, passed through rather than re-derived. Re-wording here would make two
            // sources of truth for one refusal, which is the shape this slice has already paid for.
            return new Refused(refused.why());
        }
        FixDispatch.Planned planned = (FixDispatch.Planned) plan;

        Refused unconfigured = refuseIfUnconfigured();
        if (unconfigured != null) {
            return unconfigured;
        }
        String harness = config.fix().harness().orElseThrow();
        String model = config.fix().model().orElseThrow();

        // No second unrecognised-SCM refusal here: the plan already made that decision and now
        // hands over its ANSWER. The copy that used to sit here was word-for-word identical, could
        // not be reached, and was covered by nothing.
        Optional<ScmProvider> account = machineAccounts.resolve(planned.scmType(), planned.workspace());
        if (account.isEmpty()) {
            // Two causes, one answer: no FACTORY registration at all, or one with no resolved login.
            // MachineAccounts refuses both, because packing a null login throws inside
            // MachineAccountCredential -- which on THIS arm is not a 500 but an escape from the
            // consumer, so a redelivery and an author told nothing. Never the reviewer's account
            // either: its own author allowlist skips pull requests it opened, so a fallback would
            // push a branch nobody reviews and nobody is told about.
            return new Refused("no usable factory machine account for " + planned.workspace()
                    + " — either none is registered, or the one that is has no login to "
                    + "authenticate a push as. The review bot's credential is deliberately not "
                    + "used instead");
        }
        Optional<FindingProjection.FixSpec> spec = findings.specFor(reviewId, finding.id());
        if (spec.isEmpty() || spec.get().isEmpty()) {
            // The saga already refuses a conversation-origin finding, which is the case users meet.
            // This is the second line: that gate keys on `origin`, so a review-origin row whose text
            // is somehow absent would slip past it and buy a run on a severity and a line number.
            return new Refused("that finding carries no description a fix run could work from");
        }

        // LAST of the checks, because selecting is a WRITE: it stamps last_used_at and so consumes a
        // rotation slot. Placed above any of the checks before it, that happened for every request
        // they then refused -- and it holds a decrypted key from here on.
        HarnessCredentialPool.Selection selection;
        try {
            selection = pool.select();
        } catch (IllegalStateException readFault) {
            // A read fault is NOT an empty pool, and the pool throws rather than answering Empty
            // precisely to keep the two apart. Answering "no credential is configured" to a database
            // fault would send an operator to add keys they already have.
            LOG.error("The harness credential pool could not be read for a fix run", readFault);
            return new Refused("the harness credential pool could not be read, so no key was chosen "
                    + "and nothing was spent — this is a database fault, not a missing credential");
        }
        HarnessCredentialPool.PoolMember credential;
        switch (selection) {
            case HarnessCredentialPool.Selection.Chosen chosen -> credential = chosen.member();
            case HarnessCredentialPool.Selection.Resting resting -> {
                return new Refused("no harness credential is available; capacity returns at "
                        + resting.capacityReturnsAt());
            }
            case HarnessCredentialPool.Selection.AllRejected rejected -> {
                return new Refused("all " + rejected.count() + " harness credential(s) were refused by "
                        + "their provider, and nothing recovers on its own — an operator must "
                        + "replace them");
            }
            case HarnessCredentialPool.Selection.Empty ignored -> {
                return new Refused("no harness credential is configured, so there is no key for a fix "
                        + "run to call the model with");
            }
        }

        RunCommand.ExecuteRun command = new RunCommand.ExecuteRun(planned.runId(), repo,
                FactoryCloneUrls.cloneUrl(planned.scmType(), account.get().baseUrl(), repo),
                planned.baseBranch(), planned.baseCommit(), planned.branch(),
                FixPrompt.of(spec.get()), harness, model, config.agentImage().get(harness),
                NO_EXTRA_PROTECTED_PATHS, config.wallClockSeconds(),
                credentials.packScm(planned.runId(), account.get().botUsername(), account.get().secret()),
                credentials.packHarness(planned.runId(), credential.apiKey()))
                .onExistingBranch(planned.protectedBranch());

        // Recorded BEFORE the launch, so a run can never exist on the bus without a row -- the same
        // ordering the REST endpoint keeps, and the reason RunLaunch may assume the row is there.
        // This write IS the claim: a false answer means the row, and so the claim, is already held.
        if (!runs.queued(new FactoryRunProjection.QueuedRun(planned.runId(), harness, model,
                planned.baseBranch(), planned.baseCommit(), planned.branch(),
                account.get().botUsername(), credential.id())
                .asFixFor(reviewId, threadRef, commentId))) {
            return new Refused("a fix run is already recorded at " + planned.runId()
                    + ", so nothing new was dispatched");
        }
        LOG.infof("/fix on %s dispatched run %s onto %s", reviewId, planned.runId(), planned.branch());
        return switch (launch.launch(command)) {
            case RunLaunch.Dispatched ignored -> new Dispatched(planned.runId());
            // The row already records which of these two it was, in an operator's words. The author
            // gets the half that differs: whether saying /fix again is safe.
            case RunLaunch.DefiniteMiss ignored -> new Refused("the broker did not accept the run, so "
                    + "nothing was started — ask again once it is reachable");
            case RunLaunch.Uncertain ignored -> new Refused("the run was sent and never acknowledged, "
                    + "so whether it started is unknown — an operator must resolve it, and asking "
                    + "again could start a second one");
        };
    }

    /**
     * Whether this exact comment has already bought a run.
     *
     * <p>A blank comment id is refused rather than treated as "no claim yet", and that direction is
     * the whole point: without a claim a redelivery derives a HIGHER attempt through
     * {@code nextAttempt} — which counts the row the first delivery wrote — so it derives a
     * different run id and passes the {@code ON CONFLICT (run_id)} guard that catches every other
     * duplicate.
     */
    private Refused refuseIfAlreadyBought(String reviewId, String commentId) {
        if (commentId == null || commentId.isBlank()) {
            LOG.warnf("Refusing /fix on %s: the command carried no comment id to claim against", reviewId);
            return new Refused("I could not identify the comment this command came from, "
                    + "so I cannot tell a repeat delivery from a new request — an operator should "
                    + "look at the logs");
        }
        return runs.fixRunFor(reviewId, commentId)
                .map(runId -> new Refused("this comment already started fix run " + runId
                        + ", so nothing new was dispatched"))
                .orElse(null);
    }

    /**
     * A deployment that has not named a harness and a model has not enabled {@code /fix}.
     *
     * <p>Named keys rather than a generic "not configured", because the operator who reads this in a
     * timeline needs to know which one to set. The agent image is checked alongside them: it is keyed
     * by harness name, so a harness with no image is a half-configured deployment, and the REST
     * endpoint refuses the identical shape at the identical point.
     *
     * @return the refusal, or {@code null} when the deployment is configured. Null rather than an
     *     empty {@code Optional} because the value is a control-flow carrier the caller
     *     immediately unwraps — the exact shape {@code clean-code-java.md} names, and it read
     *     worse than the {@code if} it was standing in for
     */
    private Refused refuseIfUnconfigured() {
        String harness = config.fix().harness().orElse("");
        if (harness.isBlank()) {
            return new Refused("this deployment has not enabled /fix — an operator must "
                    + "set SPIRE_FACTORY_FIX_HARNESS");
        }
        String model = config.fix().model().orElse("");
        if (model.isBlank()) {
            return new Refused("this deployment has not enabled /fix — an operator must "
                    + "set SPIRE_FACTORY_FIX_MODEL");
        }
        String image = config.agentImage().get(harness);
        if (image == null || image.isBlank()) {
            return new Refused("no agent image is configured for the '" + harness
                    + "' harness, so a fix run has nothing to execute in");
        }
        if (!pricer.isPriceable(model)) {
            // Pricing is post-hoc -- the charge lands when the run is over -- so this is the last
            // point at which an unpriceable run can be REFUSED rather than merely noticed. Every such
            // charge records as UNKNOWN, which SUM() skips, so the spend cap would be reading a total
            // that omits precisely the runs it cannot price.
            return new Refused("the model '" + model + "' has no usable pricing, so a fix "
                    + "run could not be counted against the spend cap");
        }
        return null;
    }
}
