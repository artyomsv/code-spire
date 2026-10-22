package dev.codespire.orchestrator.work;

import dev.codespire.contract.event.EventEnvelope;
import dev.codespire.contract.port.PullRequestLander;
import dev.codespire.contract.port.PullRequestLander.BranchDeletion;
import dev.codespire.contract.port.PullRequestLander.LandState;
import dev.codespire.contract.port.PullRequestLander.MergeOutcome;
import dev.codespire.contract.scm.PullRequestRef;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.scm.ScmApiException;
import dev.codespire.contract.work.WorkExecution;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkItemLifecycle;
import dev.codespire.contract.work.WorkLanding;
import dev.codespire.contract.work.WorkPolicy;
import dev.codespire.orchestrator.factory.LandSettings;
import dev.codespire.orchestrator.factory.MachineAccounts;
import dev.codespire.orchestrator.provider.ProviderClients;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.work.WorkLandDecision.Next;
import dev.codespire.orchestrator.work.WorkLandEffects.Detail;
import dev.codespire.orchestrator.work.WorkLandEffects.Effect;
import dev.codespire.orchestrator.work.WorkLandEffects.Merge;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

/**
 * Lands an approved pull request (M4 land, spec §7.3): one durable row per approved land attempt, pinned to the
 * approved head. The merge follows a committed {@code uncertain} mark, and a merge whose answer was lost is
 * recovered by reading the pull request, never by sending the merge again (ADR-043). The merge completes the
 * item; the branch delete comes after it and never holds it (§7.7). The rows themselves are {@link WorkLandEffects}'.
 *
 * <p>A code refusal (a failed check, a conflict, a branch behind its base) requests a land fix on the same pull request
 * (§7.5); the row then rests in {@code fixing}, and the next land approval starts a new row.
 */
@ApplicationScoped
public class WorkLandSweep {
    private static final Logger LOG = Logger.getLogger(WorkLandSweep.class);
    static final Duration RECHECK = Duration.ofSeconds(60), CHECKS_TIMEOUT = Duration.ofHours(6), FAILURE_BOUND = Duration.ofHours(6);
    static final int CLEANUP_TRIES = 3;
    /** Approval no longer stands: the labels, the policy or the source changed after it (review of the branch). */
    static final String LAND_POLICY_CHANGED = "land_policy_changed";
    private static final String WORK_ITEM_CHANGED = "work_item_changed", AUTHORITY_CHANGED = "authority_changed_during_read";

    @Inject DataSource dataSource;
    @Inject WorkItemStore store;
    @Inject WorkItemTransitions transitions;
    @Inject WorkLandTransitions landTransitions;
    @Inject WorkLandEffects effects;
    @Inject WorkClock clock;
    @Inject WorkSourceRegistry sources;
    @Inject MachineAccounts accounts;
    @Inject ProviderClients clients;
    @Inject LandSettings settings;
    @Inject WorkLandObservations observations;
    @Inject WorkLandFixes fixes;

    /** The FACTORY account's lander and stable provider id, the repository and the saved merge method one pass works with. */
    private record Target(PullRequestLander lander, RepoRef repo, PullRequestLander.MergeMethod method, String factoryId) {}

    @Scheduled(every = "${spire.work-land-interval:5s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void drain() {
        // Hand merges first: one this read confirms starts its cleaning row, which the same pass then drains.
        observations.drain();
        for (UUID id : effects.due(clock.now())) advance(id);
    }

    public void advance(UUID id) {
        Effect effect = effects.read(id);
        if (effect == null || !WorkLandEffects.LIVE.contains(effect.state())) return;
        WorkItemEvent item = store.load(effect.item());
        if (item == null) return;
        Target target = target(item);
        if (target == null) { failed(effect, "land_account_unavailable"); return; }
        switch (effect.state()) {
            case "merging", "uncertain" -> recover(effect, item, target);
            case "merged" -> record(effect, item, target);
            case "cleaning" -> clean(effect, item, target);
            default -> check(effect, item, target);
        }
    }

    private Target target(WorkItemEvent item) {
        Optional<ScmProvider> account = accounts.resolve(item.repositoryId());
        if (account.isEmpty()) return null;
        PullRequestLander lander = clients.pullRequestLander(account.get());
        if (!lander.available()) return null;
        RepoRef repo = sources.get(item.sourceId()).orElseThrow(() -> new IllegalStateException("Work source " + item.sourceId() + " is missing"))
                .repository();
        return new Target(lander, repo, settings.get(item.repositoryId()).mergeMethod(), account.get().botAccountId());
    }

    /** pending, checking and waiting_checks: nothing has been sent yet, so the item must still be at this attempt. */
    private void check(Effect effect, WorkItemEvent item, Target target) {
        if (!atAttempt(item, effect)) { effects.move(effect, "refused", WORK_ITEM_CHANGED, null); return; }
        LandState pr;
        PullRequestLander.AllowedMethods allowed;
        try {
            pr = target.lander().read(target.repo(), effect.pullRequest());
            allowed = target.lander().allowedMethods(target.repo());
        } catch (RuntimeException unreadable) {
            warn(effect, target, "could not be read", unreadable);
            failed(effect, "land_read_failed");
            return;
        }
        Next next = WorkLandDecision.checking(pr, effect.approvedHead(), allowed, target.method());
        if (next instanceof Next.Merge) merge(effect, item, target);
        else if (next instanceof Next.Wait) waitForChecks(effect, pr);
        else settle(effect, next, pr, null);
    }

    /** A found merge, a closed pull request, a hand-off or a land fix. */
    private void settle(Effect effect, Next next, LandState pr, String message) {
        switch (next) {
            case Next.MergedFound found -> found(effect, pr, found.mergedBy());
            case Next.Closed closed -> {
                WorkItemTransitions.Outcome outcome = landTransitions.landClosed(effect.item(), effect.id());
                effects.move(effect, "closed", outcome.status() == 200 ? null : WORK_ITEM_CHANGED, null);
            }
            case Next.Hand hand -> hand(effect, hand.reason(), new Detail(message, pr.checks()));
            case Next.Fix fix -> fix(effect, fix.reason(), pr, new Detail(message, pr.checks()));
            default -> throw new IllegalStateException("Not a settling land outcome: " + next);
        }
    }

    private void hand(Effect effect, String reason, Detail detail) {
        WorkItemTransitions.Outcome outcome = landTransitions.landHandOff(effect.item(), effect.id(), reason);
        effects.move(effect, "handed", outcome.status() == 200 ? reason : WORK_ITEM_CHANGED, detail);
    }

    /**
     * The refusal is the code's (§7.5). The decider requests the fix, or hands the item over when the attempts or the
     * item's limits are spent, and moves this row in the same transaction. The detail is kept first, so the operator
     * reads what GitHub said either way.
     */
    private void fix(Effect effect, String reason, LandState pr, Detail detail) {
        effects.keepDetail(effect, detail);
        WorkItemTransitions.Outcome outcome = landTransitions.landFix(effect.item(), effect.id(), reason,
                fixes.encode(effect.id(), WorkLandFixes.payload(reason, pr)));
        if (outcome.status() != 200) effects.move(effect, "handed", WORK_ITEM_CHANGED, null);
    }

    private void waitForChecks(Effect effect, LandState pr) {
        Instant now = clock.now();
        Instant since = effect.waitingSince() == null ? now : effect.waitingSince();
        if (!now.isBefore(since.plus(CHECKS_TIMEOUT))) { hand(effect, "land_checks_timeout", new Detail(null, pr.checks())); return; }
        effects.waitForChecks(effect, since, now.plus(RECHECK), new Detail(null, pr.checks()));
    }

    /**
     * The approval is re-observed before the merge is claimed, as delivery re-observes before its permit: a label
     * removed, a policy saved or a source disabled since then withdraws it, and the item is handed over. A ticket that
     * cannot be read now cannot confirm it either, so the row waits and reads again.
     */
    private void merge(Effect effect, WorkItemEvent item, Target target) {
        WorkItemTransitions.Observation observed = transitions.observe(item.sourceId(), item.issue());
        if (observed.source().enabled() && observed.evidence().failure() != null) {
            LOG.warnf("Land effect %s could not read its ticket (%s); the merge waits for it", effect.id(), observed.evidence().failure());
            failed(effect, "land_read_failed");
            return;
        }
        String method = LandSettings.wire(target.method());
        String refused = claimMerge(effect, method, observed);
        if (LAND_POLICY_CHANGED.equals(refused)) { hand(effect, LAND_POLICY_CHANGED, null); return; }
        if (refused != null) return;
        sendMerge(effect, target, method);
    }

    private void sendMerge(Effect effect, Target target, String method) {
        Effect sent = effect.at("uncertain");
        MergeOutcome outcome;
        try { outcome = target.lander().merge(target.repo(), effect.pullRequest(), effect.approvedHead(), target.method()); }
        catch (RuntimeException lost) {
            warn(effect, target, "lost its merge answer", lost);
            effects.note(sent, "merge_outcome_unknown");
            return;
        }
        switch (outcome) {
            case MergeOutcome.Merged merged -> {
                markMerged(sent, new Merge(merged.sha(), WorkLanding.FACTORY, method, effect.approvedHead()));
                advance(effect.id());
            }
            // The row stays uncertain: the next pass only reads.
            case MergeOutcome.Uncertain uncertain -> effects.note(sent, "merge_outcome_unknown");
            case MergeOutcome.HeadConflict conflict -> afterConflict(sent, target);
            case MergeOutcome.Refused refused -> afterRefusal(sent, target, refused);
        }
    }

    /**
     * Commits {@code uncertain} before the merge call, under the item lock, and only while the item is still at this
     * attempt and its approval still stands: a takeover that refused the row first wins, and a merge that was claimed is
     * never re-sent blind. Locks the source and policy first, in the order every transition takes them.
     *
     * @return null when claimed; {@link #LAND_POLICY_CHANGED} when the approval no longer stands; any other reason
     *     leaves the row for the next pass
     */
    private String claimMerge(Effect effect, String method, WorkItemTransitions.Observation observed) {
        return QuarkusTransaction.requiringNew().call(() -> {
            try (Connection c = dataSource.getConnection()) {
                if (!transitions.current(c, observed)) return AUTHORITY_CHANGED;
                WorkItemTransitions.lockItem(c, effect.item());
                List<EventEnvelope> history = store.history(effect.item());
                WorkItemEvent item = (WorkItemEvent) history.getLast().payload();
                if (!atAttempt(item, effect)) return WORK_ITEM_CHANGED;
                if (!approvalStands(history, item, effect, observed)) return LAND_POLICY_CHANGED;
                return WorkLandEffects.claimMerge(c, effect, method) ? null : WORK_ITEM_CHANGED;
            } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        });
    }

    /** The selection, the policy revision and the source the land attempt started under, with land still {@code approve}. */
    private boolean approvalStands(List<EventEnvelope> history, WorkItemEvent item, Effect effect, WorkItemTransitions.Observation observed) {
        if (!observed.source().enabled()) return false;
        WorkItemEvent admitted = WorkItemTransitions.facts(history).stream()
                .filter(event -> "PHASE_STARTED".equals(event.milestone()) && effect.id().equals(event.progress().attemptId()))
                .findFirst().orElse(null);
        if (admitted == null) return false;
        WorkPolicy.Selection selection = transitions.select(observed, item);
        return selection.equals(admitted.policy()) && observed.policy().revision() == admitted.policyRevision()
                && "approve".equals(selection.effective().get(WorkPolicy.Phase.LAND));
    }

    /** 409: the head or the base moved and nothing was merged. Same head → check again next pass; a new head is handed. */
    private void afterConflict(Effect effect, Target target) {
        LandState pr = readOrNull(effect, target);
        if (pr == null) { effects.move(effect, "checking", "land_read_failed", null); return; }
        if (pr.merged()) { found(effect, pr, pr.mergedBy()); return; }
        if (effect.approvedHead().equals(pr.headSha())) { effects.move(effect, "checking", null, null); return; }
        hand(effect, "land_head_moved", new Detail(null, pr.checks()));
    }

    /** Any other answer below 500 is definite. Re-read once: a code cause is a fix, anything else is GitHub's refusal. */
    private void afterRefusal(Effect effect, Target target, MergeOutcome.Refused refused) {
        LandState pr = readOrNull(effect, target);
        if (pr != null && pr.merged()) { found(effect, pr, pr.mergedBy()); return; }
        if (pr != null && (refused.status() == 405 || refused.status() == 422)) {
            // The method was checked before the call, so it is not read again here (A5).
            Next next = WorkLandDecision.checking(pr, effect.approvedHead(), new PullRequestLander.AllowedMethods(false, Set.of()), target.method());
            if (next instanceof Next.Fix || next instanceof Next.Closed) { settle(effect, next, pr, refused.message()); return; }
        }
        hand(effect, "land_merge_refused", new Detail(refused.message(), pr == null ? null : pr.checks()));
    }

    /**
     * uncertain: a merge was sent and its answer is unknown, so this only reads. Merged → recorded as the factory's
     * only when it is the factory's (see {@link #factoryMerge}), else as the merger GitHub names; open at the approved
     * head → back to checking, which may merge on a later pass, since the sha pin makes a second merge of other code
     * impossible, or, on an item no longer at this attempt, read again (see {@link #heldOpen}); anything else → handed.
     * Runs on a suspended item too (Review Focus 13).
     */
    private void recover(Effect effect, WorkItemEvent item, Target target) {
        LandState pr = readOrNull(effect, target);
        if (pr == null) { failed(effect, "land_read_failed"); return; }
        if (pr.merged()) {
            if (!factoryMerge(effect, item, target, pr)) { found(effect, pr, pr.mergedBy()); return; }
            // A row claimed before the method was kept with the claim names none.
            String sent = effect.method() == null ? WorkLanding.UNKNOWN_METHOD : effect.method();
            if (markMerged(effect, new Merge(pr.mergeCommitSha(), WorkLanding.FACTORY, sent, pr.headSha()))) advance(effect.id());
            return;
        }
        if ("closed".equals(pr.state())) { settle(effect, new Next.Closed(), pr, null); return; }
        if (!effect.approvedHead().equals(pr.headSha())) { hand(effect, "land_head_moved", new Detail(null, pr.checks())); return; }
        if (atAttempt(item, effect)) effects.move(effect, "checking", null, null);
        else heldOpen(effect);
    }

    /**
     * A sent merge on an item taken over, its pull request still open at the approved head. GitHub can still finish the
     * merge (A5), and the factory's own merged webhook is left to this row, so the row keeps reading and resume stays
     * refused. Once the head moves or the pull request closes no merge can follow; after {@link #FAILURE_BOUND} of
     * open reads the row is refused as the takeover's.
     */
    private void heldOpen(Effect effect) {
        Instant now = clock.now();
        Instant since = effect.failingSince() == null ? now : effect.failingSince();
        if (!now.isBefore(since.plus(FAILURE_BOUND))) { effects.move(effect, "refused", "publication_held_by_human", null); return; }
        effects.backOff(effect, "land_merge_unconfirmed", since, now.plus(RECHECK));
    }

    /**
     * A merge found after a lost answer is the factory's only at the approved head, which is all the sent merge could
     * have merged, and under the factory's own id, current or recorded at the build claim. A person who merged a moved
     * head, or the approved one, keeps the merge (anti-goal §4, A2).
     */
    private static boolean factoryMerge(Effect effect, WorkItemEvent item, Target target, LandState pr) {
        String merger = pr.mergedBy();
        if (!effect.approvedHead().equals(pr.headSha()) || merger == null || merger.isBlank()) return false;
        return merger.equals(target.factoryId()) || item.control() != null && merger.equals(item.control().factoryActor());
    }

    /**
     * A merge someone else made, or one this effect's re-read found: GitHub names the merger, but not the method, so
     * none is claimed.
     */
    private void found(Effect effect, LandState pr, String mergedBy) {
        if (markMerged(effect, new Merge(pr.mergeCommitSha(), mergedBy, WorkLanding.UNKNOWN_METHOD, pr.headSha()))) advance(effect.id());
    }

    /** {@code merge.method()} is the method the factory sent for its own merge; {@link WorkLanding#UNKNOWN_METHOD} for any other. */
    private boolean markMerged(Effect effect, Merge merge) {
        if (merge.commit() == null || !merge.commit().matches("[0-9a-f]{40}") || merge.mergedBy() == null || merge.mergedBy().isBlank()) {
            failed(effect, "land_merge_unreadable");
            return false;
        }
        return effects.markMerged(effect, merge);
    }

    /** merged: WORK_ITEM_LANDED completes the item at once (§7.7); the branch delete follows and never holds it. */
    private void record(Effect effect, WorkItemEvent item, Target target) {
        WorkExecution execution = item.progress().execution();
        PullRequestRef pullRequest = execution == null ? null : execution.pullRequest();
        if (pullRequest == null || pullRequest.number() != effect.pullRequest()) { effects.move(effect, "done", WORK_ITEM_CHANGED, null); return; }
        WorkLanding landing = new WorkLanding(pullRequest, effect.mergeCommit(), effect.method(), effect.mergedBy(), clock.now());
        WorkItemTransitions.Outcome outcome = landTransitions.landed(effect.item(), effect.id(), landing);
        if (outcome.status() != 200) { effects.move(effect, "done", outcome.reason(), null); return; }
        effects.move(effect, "cleaning", null, null);
        clean(effect.at("cleaning"), store.load(effect.item()), target);
    }

    /** Delete the item branch only where it was merged; after three failed tries the operator is told (A6, A13). */
    private void clean(Effect effect, WorkItemEvent item, Target target) {
        String branch = item.control() == null ? null : item.control().branch();
        String expected = effect.mergedHead() == null ? effect.approvedHead() : effect.mergedHead();
        BranchDeletion result;
        try { result = branch == null ? BranchDeletion.STILL_PRESENT : target.lander().deleteBranch(target.repo(), branch, expected); }
        catch (RuntimeException fault) {
            warn(effect, target, "could not delete its branch", fault);
            result = BranchDeletion.STILL_PRESENT;
        }
        if (result == BranchDeletion.DELETED || result == BranchDeletion.ABSENT) { effects.move(effect, "done", null, null); return; }
        int tries = effect.cleanupTries() + 1;
        effects.cleanupFailed(effect, tries, tries >= CLEANUP_TRIES);
    }

    private LandState readOrNull(Effect effect, Target target) {
        try { return target.lander().read(target.repo(), effect.pullRequest()); }
        catch (RuntimeException unreadable) {
            warn(effect, target, "could not be read", unreadable);
            return null;
        }
    }

    /**
     * A forge call that failed, named by the effect, the repository and the exception type with its HTTP status. The
     * message is left out: an adapter's message may quote a response body.
     */
    private static void warn(Effect effect, Target target, String what, RuntimeException failure) {
        LOG.warnf("Land effect %s on %s: pull request #%d %s (%s)", effect.id(), target.repo().full(), effect.pullRequest(), what, cause(failure));
    }

    /** The exception's type, with the HTTP status when the forge answered one. */
    static String cause(RuntimeException failure) {
        String type = failure.getClass().getSimpleName();
        return failure instanceof ScmApiException api ? type + " HTTP " + api.status() : type;
    }

    private static boolean atAttempt(WorkItemEvent item, Effect effect) {
        return item != null && item.generation() == effect.generation() && "land".equals(item.phase())
                && "active".equals(item.workflowStatus()) && effect.id().equals(item.progress().attemptId());
    }

    /**
     * A pass that could not read GitHub or act as the factory. The row is tried again after {@link #RECHECK}, so it
     * neither reads the forge on every sweep nor crowds newer rows out of the drain. After {@link #FAILURE_BOUND} of
     * failures a row with no merge sent is handed with this reason; a row past the merge call, or one that found the
     * pull request merged but could not read the merge, is never handed, because the merge may have happened, and says
     * {@code land_merge_unconfirmed} while it keeps reading.
     */
    private void failed(Effect effect, String reason) {
        Instant now = clock.now();
        Instant since = effect.failingSince() == null ? now : effect.failingSince();
        boolean exhausted = !now.isBefore(since.plus(FAILURE_BOUND));
        if (exhausted && WorkLandEffects.BEFORE_MERGE.contains(effect.state()) && WorkItemLifecycle.LAND_HANDOFF_REASONS.contains(reason)) {
            hand(effect, reason, null);
            return;
        }
        effects.backOff(effect, exhausted ? "land_merge_unconfirmed" : reason, since, now.plus(RECHECK));
    }
}
