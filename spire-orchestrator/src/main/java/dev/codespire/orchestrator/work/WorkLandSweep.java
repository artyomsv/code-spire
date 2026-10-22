package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.port.PullRequestLander;
import dev.codespire.contract.port.PullRequestLander.BranchDeletion;
import dev.codespire.contract.port.PullRequestLander.LandState;
import dev.codespire.contract.port.PullRequestLander.MergeOutcome;
import dev.codespire.contract.scm.PullRequestRef;
import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.work.WorkExecution;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkItemLifecycle;
import dev.codespire.contract.work.WorkLanding;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.orchestrator.factory.LandSettings;
import dev.codespire.orchestrator.factory.MachineAccounts;
import dev.codespire.orchestrator.provider.ProviderClients;
import dev.codespire.orchestrator.provider.ScmProvider;
import dev.codespire.orchestrator.work.WorkLandDecision.Next;
import io.quarkus.narayana.jta.QuarkusTransaction;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * Lands an approved pull request (M4 land, spec §7.3): one durable row per approved land attempt, pinned to the
 * approved head. The merge follows a committed {@code uncertain} mark, and a merge whose answer was lost is
 * recovered by reading the pull request, never by sending the merge again (ADR-043). The merge completes the
 * item; the branch delete comes after it and never holds it (§7.7).
 *
 * <p>A code refusal (a failed check, a conflict, a branch behind its base) requests a land fix on the same pull request
 * (§7.5); the row then rests in {@code fixing}, and the next land approval starts a new row.
 */
@ApplicationScoped
public class WorkLandSweep {
    static final Duration RECHECK = Duration.ofSeconds(60), CHECKS_TIMEOUT = Duration.ofHours(6), FAILURE_BOUND = Duration.ofHours(6);
    static final int CLEANUP_TRIES = 3;
    /** The states the sweep still drains; done, handed, closed, refused and fixing are another owner's or final. */
    static final Set<String> LIVE = Set.of("pending", "checking", "waiting_checks", "merging", "uncertain", "merged", "cleaning");
    /** No merge has been sent from these, so a row stuck in one can be handed to the operator. */
    static final Set<String> BEFORE_MERGE = Set.of("pending", "checking", "waiting_checks");

    @Inject DataSource dataSource;
    @Inject WorkItemStore store;
    @Inject WorkItemTransitions transitions;
    @Inject WorkClock clock;
    @Inject WorkSourceRegistry sources;
    @Inject MachineAccounts accounts;
    @Inject ProviderClients clients;
    @Inject LandSettings settings;
    @Inject ObjectMapper mapper;
    @Inject EncryptionService encryption;
    @Inject WorkLandObservations observations;
    @Inject WorkLandFixes fixes;

    record Effect(UUID id, String item, long generation, long pullRequest, String approvedHead, String state, String mergeCommit,
                  String mergedBy, String method, String mergedHead, int cleanupTries, Instant waitingSince, Instant failingSince) {
        Effect at(String next) {
            return new Effect(id, item, generation, pullRequest, approvedHead, next, mergeCommit, mergedBy, method, mergedHead, cleanupTries,
                    waitingSince, failingSince);
        }
    }

    /** What the operator reads: GitHub's own message and the check summary. Both may quote source, so encrypted. */
    public record Detail(String message, PullRequestLander.CheckSummary checks) {}

    /** The FACTORY account's lander and stable provider id, the repository and the saved merge method one pass works with. */
    private record Target(PullRequestLander lander, RepoRef repo, PullRequestLander.MergeMethod method, String factoryId) {}

    @Scheduled(every = "${spire.work-land-interval:5s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void drain() {
        // Hand merges first: one this read confirms starts its cleaning row, which the same pass then drains.
        observations.drain();
        List<UUID> ids = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT effect_id FROM work_land_effect WHERE state = ANY(?) AND (next_check_at IS NULL OR next_check_at <= ?)"
                        + " ORDER BY created_at LIMIT 20")) {
            ps.setArray(1, c.createArrayOf("text", LIVE.toArray()));
            ps.setTimestamp(2, Timestamp.from(clock.now()));
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getObject(1, UUID.class)); }
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        for (UUID id : ids) advance(id);
    }

    public void advance(UUID id) {
        Effect effect = read(id);
        if (effect == null || !LIVE.contains(effect.state())) return;
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
        if (!atAttempt(item, effect)) { update(effect, "refused", "work_item_changed", null); return; }
        LandState pr;
        PullRequestLander.AllowedMethods allowed;
        try {
            pr = target.lander().read(target.repo(), effect.pullRequest());
            allowed = target.lander().allowedMethods(target.repo());
        } catch (RuntimeException unreadable) { failed(effect, "land_read_failed"); return; }
        Next next = WorkLandDecision.checking(pr, effect.approvedHead(), allowed, target.method());
        if (next instanceof Next.Merge) merge(effect, target);
        else if (next instanceof Next.Wait) waitForChecks(effect, pr);
        else settle(effect, next, pr, null);
    }

    /** A found merge, a closed pull request, a hand-off or a land fix. */
    private void settle(Effect effect, Next next, LandState pr, String message) {
        switch (next) {
            case Next.MergedFound found -> found(effect, pr, found.mergedBy());
            case Next.Closed closed -> {
                WorkItemTransitions.Outcome outcome = transitions.landClosed(effect.item(), effect.id());
                update(effect, "closed", outcome.status() == 200 ? null : "work_item_changed", null);
            }
            case Next.Hand hand -> hand(effect, hand.reason(), new Detail(message, pr.checks()));
            case Next.Fix fix -> fix(effect, fix.reason(), pr, new Detail(message, pr.checks()));
            default -> throw new IllegalStateException("Not a settling land outcome: " + next);
        }
    }

    private void hand(Effect effect, String reason, Detail detail) {
        WorkItemTransitions.Outcome outcome = transitions.landHandOff(effect.item(), effect.id(), reason);
        update(effect, "handed", outcome.status() == 200 ? reason : "work_item_changed", detail);
    }

    /**
     * The refusal is the code's (§7.5). The decider requests the fix, or hands the item over when the attempts or the
     * item's limits are spent, and moves this row in the same transaction. The detail is kept first, so the operator
     * reads what GitHub said either way.
     */
    private void fix(Effect effect, String reason, LandState pr, Detail detail) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE work_land_effect SET detail=?,updated_at=now() WHERE effect_id=? AND state=?")) {
            ps.setBytes(1, encode(effect, detail)); ps.setObject(2, effect.id()); ps.setString(3, effect.state());
            ps.executeUpdate();
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        WorkItemTransitions.Outcome outcome = transitions.landFix(effect.item(), effect.id(), reason,
                fixes.encode(effect.id(), WorkLandFixes.payload(reason, pr)));
        if (outcome.status() != 200) update(effect, "handed", "work_item_changed", null);
    }

    private void waitForChecks(Effect effect, LandState pr) {
        Instant now = clock.now();
        Instant since = effect.waitingSince() == null ? now : effect.waitingSince();
        if (!now.isBefore(since.plus(CHECKS_TIMEOUT))) { hand(effect, "land_checks_timeout", new Detail(null, pr.checks())); return; }
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                UPDATE work_land_effect SET state='waiting_checks',reason=NULL,waiting_since=?,next_check_at=?,detail=?,failing_since=NULL,updated_at=now()
                WHERE effect_id=? AND state=?
                """)) {
            ps.setTimestamp(1, Timestamp.from(since)); ps.setTimestamp(2, Timestamp.from(now.plus(RECHECK)));
            ps.setBytes(3, encode(effect, new Detail(null, pr.checks()))); ps.setObject(4, effect.id()); ps.setString(5, effect.state());
            ps.executeUpdate();
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }

    private void merge(Effect effect, Target target) {
        String method = LandSettings.wire(target.method());
        if (!claimMerge(effect, method)) return;
        Effect sent = effect.at("uncertain");
        MergeOutcome outcome;
        try { outcome = target.lander().merge(target.repo(), effect.pullRequest(), effect.approvedHead(), target.method()); }
        catch (RuntimeException lost) { note(sent, "merge_outcome_unknown"); return; }
        switch (outcome) {
            case MergeOutcome.Merged merged -> {
                markMerged(sent, merged.sha(), WorkLanding.FACTORY, effect.approvedHead(), method);
                advance(effect.id());
            }
            // The row stays uncertain: the next pass only reads.
            case MergeOutcome.Uncertain uncertain -> note(sent, "merge_outcome_unknown");
            case MergeOutcome.HeadConflict conflict -> afterConflict(sent, target);
            case MergeOutcome.Refused refused -> afterRefusal(sent, target, refused);
        }
    }

    /**
     * Commits {@code uncertain} before the merge call, under the item lock, and only while the item is still at
     * this attempt: a takeover that refused the row first wins, and a merge that was claimed is never re-sent blind.
     * The method sent is kept with the claim, so a recovery on a later pass records it, not the setting saved then.
     */
    private boolean claimMerge(Effect effect, String method) {
        return QuarkusTransaction.requiringNew().call(() -> {
            try (Connection c = dataSource.getConnection()) {
                WorkItemTransitions.lockItem(c, effect.item());
                if (!atAttempt(store.load(effect.item()), effect)) return false;
                try (PreparedStatement ps = c.prepareStatement(
                        "UPDATE work_land_effect SET state='uncertain',reason=NULL,method=?,next_check_at=NULL,failing_since=NULL,updated_at=now() WHERE effect_id=? AND state=?")) {
                    ps.setString(1, method); ps.setObject(2, effect.id()); ps.setString(3, effect.state());
                    return ps.executeUpdate() == 1;
                }
            } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        });
    }

    /** 409: the head or the base moved and nothing was merged. Same head → check again next pass; a new head is handed. */
    private void afterConflict(Effect effect, Target target) {
        LandState pr = readOrNull(effect, target);
        if (pr == null) { update(effect, "checking", "land_read_failed", null); return; }
        if (pr.merged()) { found(effect, pr, pr.mergedBy()); return; }
        if (effect.approvedHead().equals(pr.headSha())) { update(effect, "checking", null, null); return; }
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
     * impossible; anything else → handed. Runs on a suspended item too (Review Focus 13).
     */
    private void recover(Effect effect, WorkItemEvent item, Target target) {
        LandState pr = readOrNull(effect, target);
        if (pr == null) { failed(effect, "land_read_failed"); return; }
        if (pr.merged()) {
            if (!factoryMerge(effect, item, target, pr)) { found(effect, pr, pr.mergedBy()); return; }
            // A row claimed before the method was kept with the claim names none.
            String sent = effect.method() == null ? WorkLanding.UNKNOWN_METHOD : effect.method();
            if (markMerged(effect, pr.mergeCommitSha(), WorkLanding.FACTORY, pr.headSha(), sent)) advance(effect.id());
            return;
        }
        if ("closed".equals(pr.state())) { settle(effect, new Next.Closed(), pr, null); return; }
        if (effect.approvedHead().equals(pr.headSha())) {
            if (atAttempt(item, effect)) update(effect, "checking", null, null);
            else update(effect, "refused", "publication_held_by_human", null);
            return;
        }
        hand(effect, "land_head_moved", new Detail(null, pr.checks()));
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
        if (markMerged(effect, pr.mergeCommitSha(), mergedBy, pr.headSha(), WorkLanding.UNKNOWN_METHOD)) advance(effect.id());
    }

    /** @param method the method the factory sent for its own merge; {@link WorkLanding#UNKNOWN_METHOD} for any other */
    private boolean markMerged(Effect effect, String sha, String mergedBy, String head, String method) {
        if (sha == null || !sha.matches("[0-9a-f]{40}") || mergedBy == null || mergedBy.isBlank()) {
            failed(effect, "land_merge_unreadable");
            return false;
        }
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                UPDATE work_land_effect SET state='merged',reason=NULL,merge_commit=?,merged_by=?,method=?,merged_head=?,next_check_at=NULL,failing_since=NULL,updated_at=now()
                WHERE effect_id=? AND state=?
                """)) {
            ps.setString(1, sha); ps.setString(2, mergedBy); ps.setString(3, method); ps.setString(4, head);
            ps.setObject(5, effect.id()); ps.setString(6, effect.state());
            return ps.executeUpdate() == 1;
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }

    /** merged: WORK_ITEM_LANDED completes the item at once (§7.7); the branch delete follows and never holds it. */
    private void record(Effect effect, WorkItemEvent item, Target target) {
        WorkExecution execution = item.progress().execution();
        PullRequestRef pullRequest = execution == null ? null : execution.pullRequest();
        if (pullRequest == null || pullRequest.number() != effect.pullRequest()) { update(effect, "done", "work_item_changed", null); return; }
        WorkLanding landing = new WorkLanding(pullRequest, effect.mergeCommit(), effect.method(), effect.mergedBy(), clock.now());
        WorkItemTransitions.Outcome outcome = transitions.landed(effect.item(), effect.id(), landing);
        if (outcome.status() != 200) { update(effect, "done", outcome.reason(), null); return; }
        update(effect, "cleaning", null, null);
        clean(effect.at("cleaning"), store.load(effect.item()), target);
    }

    /** Delete the item branch only where it was merged; after three failed tries the operator is told (A6, A13). */
    private void clean(Effect effect, WorkItemEvent item, Target target) {
        String branch = item.control() == null ? null : item.control().branch();
        String expected = effect.mergedHead() == null ? effect.approvedHead() : effect.mergedHead();
        BranchDeletion result;
        try { result = branch == null ? BranchDeletion.STILL_PRESENT : target.lander().deleteBranch(target.repo(), branch, expected); }
        catch (RuntimeException fault) { result = BranchDeletion.STILL_PRESENT; }
        if (result == BranchDeletion.DELETED || result == BranchDeletion.ABSENT) { update(effect, "done", null, null); return; }
        int tries = effect.cleanupTries() + 1;
        boolean exhausted = tries >= CLEANUP_TRIES;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE work_land_effect SET cleanup_tries=?,state=?,note=?,updated_at=now() WHERE effect_id=? AND state='cleaning'")) {
            ps.setInt(1, tries); ps.setString(2, exhausted ? "done" : "cleaning"); ps.setString(3, exhausted ? "branch_delete_failed" : null);
            ps.setObject(4, effect.id());
            ps.executeUpdate();
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }

    private LandState readOrNull(Effect effect, Target target) {
        try { return target.lander().read(target.repo(), effect.pullRequest()); }
        catch (RuntimeException unreadable) { return null; }
    }

    private static boolean atAttempt(WorkItemEvent item, Effect effect) {
        return item != null && item.generation() == effect.generation() && "land".equals(item.phase())
                && "active".equals(item.workflowStatus()) && effect.id().equals(item.progress().attemptId());
    }

    /** A state change guarded by the state this pass read, so a stale pass never rewinds another's progress. */
    private void update(Effect effect, String state, String reason, Detail detail) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                UPDATE work_land_effect SET state=?,reason=?,detail=COALESCE(?,detail),next_check_at=NULL,failing_since=NULL,updated_at=now()
                WHERE effect_id=? AND state=?
                """)) {
            ps.setString(1, state); ps.setString(2, reason); ps.setBytes(3, detail == null ? null : encode(effect, detail));
            ps.setObject(4, effect.id()); ps.setString(5, effect.state());
            ps.executeUpdate();
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }

    /** Why the row did not move this pass; the next pass tries again. */
    private void note(Effect effect, String reason) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE work_land_effect SET reason=?,updated_at=now() WHERE effect_id=? AND state=?")) {
            ps.setString(1, reason); ps.setObject(2, effect.id()); ps.setString(3, effect.state());
            ps.executeUpdate();
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
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
        if (exhausted && BEFORE_MERGE.contains(effect.state()) && WorkItemLifecycle.LAND_HANDOFF_REASONS.contains(reason)) {
            hand(effect, reason, null);
            return;
        }
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE work_land_effect SET reason=?,failing_since=?,next_check_at=?,updated_at=now() WHERE effect_id=? AND state=?")) {
            ps.setString(1, exhausted ? "land_merge_unconfirmed" : reason); ps.setTimestamp(2, Timestamp.from(since));
            ps.setTimestamp(3, Timestamp.from(now.plus(RECHECK))); ps.setObject(4, effect.id()); ps.setString(5, effect.state());
            ps.executeUpdate();
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }

    private byte[] encode(Effect effect, Detail detail) {
        try { return encryption.encrypt(mapper.writeValueAsBytes(detail), "work-land-detail:" + effect.id()); }
        catch (IOException failure) { throw new IllegalStateException("Cannot encode the land detail", failure); }
    }

    /** The operator's view of a row's detail; null when it holds none. */
    Detail detail(UUID effect, byte[] stored) {
        if (stored == null) return null;
        try { return mapper.readValue(encryption.decrypt(stored, "work-land-detail:" + effect), Detail.class); }
        catch (IOException failure) { throw new IllegalStateException("Cannot read the land detail of " + effect, failure); }
    }

    private Effect read(UUID id) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("SELECT * FROM work_land_effect WHERE effect_id=?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Effect(id, rs.getString("work_item_id"), rs.getLong("generation"), rs.getLong("pr_number"), rs.getString("approved_head"),
                        rs.getString("state"), rs.getString("merge_commit"), rs.getString("merged_by"), rs.getString("method"),
                        rs.getString("merged_head"), rs.getInt("cleanup_tries"), instant(rs, "waiting_since"), instant(rs, "failing_since"));
            }
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
