package dev.codespire.orchestrator.work;

import dev.codespire.contract.scm.RepoRef;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkPreparation;
import dev.codespire.orchestrator.factory.BuildDefaults;
import dev.codespire.orchestrator.provider.ProviderClients;
import dev.codespire.orchestrator.provider.ProviderRole;
import dev.codespire.orchestrator.repository.RepositoryAccounts;
import dev.codespire.worksource.WorkSource;
import dev.codespire.worksource.WorkTicket;
import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.*;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

/**
 * Prepares a work item from the one ticket a person wrote (M3.5 part C).
 *
 * <p>Until now a human had to create a specification ticket, create a plan ticket, paste JSON into it,
 * type a branch, a commit, a harness and a model, and press two buttons — for every item. Everything
 * that list contains is either already known (the repository's build setup) or already written (the
 * ticket). This sweep does it: the ticket becomes the specification, the plan is the single step the
 * schema requires, the base commit is the head of the default branch when the task is prepared, and the
 * registration is the system's own act.
 *
 * <p>It registers through {@link WorkItemTransitions#prepare}, the same entry point the manual form
 * uses, so it inherits that path's authority protocol whole: the source and policy versions are
 * compared under lock, the item's revision, status, attempts and open gate are checked, and the
 * decision is appended in one transaction. A sweep that loses a race is refused exactly as a second
 * operator would be.
 */
@ApplicationScoped
public class WorkPreparationSweep {
    private static final Logger LOG = Logger.getLogger(WorkPreparationSweep.class);

    @Inject DataSource dataSource;
    @Inject WorkItemStore store;
    @Inject WorkItemTransitions transitions;
    @Inject WorkItemArtifacts artifacts;
    @Inject WorkPreparationComposer composer;
    @Inject WorkSourceRegistry sources;
    @Inject BuildDefaults defaults;
    @Inject RepositoryAccounts accounts;
    @Inject ProviderClients clients;
    @Inject WorkSourceEffects effects;

    /** Read-then-write, so a slow forge cannot hold an item lock; the write re-checks under the lock. */
    private static final int MAX_ITEMS_PER_SWEEP = 5;

    /** Bounded backoff. A repository whose model has no rate is not fixed by asking again in a second. */
    private static final Duration FIRST_BACKOFF = Duration.ofSeconds(30), MAX_BACKOFF = Duration.ofMinutes(30);

    @Scheduled(every = "${spire.work-preparation-interval:20s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    public void sweep() {
        for (String id : candidates()) {
            try { prepare(id); }
            catch (RuntimeException failure) {
                // One item's forge, tracker or catalogue fault must not stop the others.
                LOG.warnf(failure, "work item %s could not be prepared automatically", id);
                record(id, "preparation_failed");
            }
        }
    }

    /**
     * Items waiting for a specification whose last automatic attempt, if any, is due to be retried.
     *
     * <p>The workflow reason is the trigger, and the attempt row is the only thing that holds an item
     * back — writing a failure onto the item itself would make it stop matching this query for ever.
     *
     * <p>A repository with no build setup is NOT filtered out here. It is picked up and refused with
     * {@code build_defaults_missing}, because an item stuck for a reason nobody is told about is the
     * defect this slice exists to remove; the backoff keeps the cost of saying so down.
     */
    private List<String> candidates() {
        List<String> ids = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                SELECT i.id FROM work_item i
                  LEFT JOIN work_item_preparation_attempt a
                         ON a.work_item_id = i.id AND a.generation = i.generation
                 WHERE i.phase = 'spec' AND i.workflow_status = 'awaiting_input'
                   AND i.reason = 'specification_required'
                   AND (a.retry_after IS NULL OR a.retry_after <= now())
                 ORDER BY i.updated_at
                 LIMIT ?
                """)) {
            ps.setInt(1, MAX_ITEMS_PER_SWEEP);
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getString(1)); }
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        return ids;
    }

    /**
     * Compose this item again on purpose, whatever it already has.
     *
     * <p>The ticket is the source of the specification, so an edited ticket means the prepared task is
     * out of date — but only a person can say whether that edit was meant for this task. Preparing
     * again writes NEW stored rows and supersedes an open decision (the shared registration path does
     * that), so nothing rewrites the text somebody already approved.
     */
    /** Whether the item is now prepared, and the reason to show either way. */
    public record Result(boolean prepared, String reason) {}

    public Result prepareAgain(String id) {
        clear(id, store.load(id) == null ? 0 : store.load(id).generation());
        return prepare(id, true);
    }

    /** Compose, store and register one item. Every refusal is recorded as this item's health. */
    Result prepare(String id) { return prepare(id, false); }

    Result prepare(String id, boolean again) {
        WorkItemEvent item = store.load(id);
        if (item == null) return new Result(false, "work_item_unknown");
        if (item.preparation() != null && !again) return new Result(false, "already_prepared");
        BuildDefaults.Defaults setup = defaults.get(item.repositoryId());
        if (!setup.set()) { record(id, "build_defaults_missing"); return new Result(false, "build_defaults_missing"); }

        var observed = transitions.observe(item.sourceId(), item.issue());
        if (observed.evidence().failure() != null) { record(id, observed.evidence().failure()); return new Result(false, observed.evidence().failure()); }
        if (!(sources.client(observed.source()).fetch(item.issue()) instanceof WorkSource.Fetch.Found found)) {
            record(id, "artifacts_unavailable"); return new Result(false, "artifacts_unavailable");
        }

        String specification, plan;
        try {
            specification = composer.specification(found.ticket());
            plan = composer.plan(specification);
        } catch (WorkPreparationComposer.NotComposable refused) { record(id, refused.reason()); return new Result(false, refused.reason()); }

        String head;
        try { head = head(item, setup.baseBranch()); }
        catch (RuntimeException unavailable) { record(id, "branch_head_unconfirmed"); return new Result(false, "branch_head_unconfirmed"); }

        UUID specificationId, planId;
        try (Connection c = dataSource.getConnection()) {
            // Written before the item transaction on purpose: encryption and two inserts have no business
            // inside the lock that guards the item's history. A refused registration leaves these rows
            // unreferenced — they are immutable and nothing reads them without a preparation that names
            // them, and the event that DOES name them always points at bytes that already exist.
            specificationId = artifacts.store(c, id, WorkItemArtifacts.Kind.SPEC, specification);
            planId = artifacts.store(c, id, WorkItemArtifacts.Kind.PLAN, plan);
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }

        WorkPreparation prepared = new WorkPreparation(
                new WorkPreparation.Artifact(item.issue(), WorkPreparation.digest(specification),
                        WorkPreparation.Origin.STORED, specificationId),
                new WorkPreparation.Artifact(item.issue(), WorkPreparation.digest(plan),
                        WorkPreparation.Origin.STORED, planId),
                setup.baseBranch(), head, setup.harness(), setup.model(),
                "system:build-defaults@" + setup.revision(), WorkPreparation.STORED_BINDING);

        var outcome = transitions.prepare(id, store.history(id).size(), prepared);
        if (outcome.status() != 200) {
            String refusal = outcome.detail() == null ? outcome.reason() : outcome.detail();
            record(id, refusal); return new Result(false, refusal);
        }
        clear(id, item.generation());
        comment(id, item, setup, head);
        // The reason is the workflow's own: a plan gate opens as approval_required, an autonomous item
        // goes straight on. Both are prepared; only the sentence differs.
        return new Result(true, outcome.reason());
    }

    /** The head of the default base branch, through the repository's own account (decision 2A). */
    private String head(WorkItemEvent item, String branch) {
        var account = accounts.resolve(item.repositoryId(), ProviderRole.FACTORY)
                .or(() -> accounts.resolve(item.repositoryId(), ProviderRole.REVIEWER))
                .orElseThrow(() -> new IllegalStateException("repository_account_missing"));
        var source = sources.get(item.sourceId()).orElseThrow(() -> new IllegalStateException("source_unavailable"));
        RepoRef repository = source.repository();
        return clients.diffSource(account).fetchBranchHead(repository, branch);
    }

    /**
     * The readable copy on the ticket. It is an EFFECT, not a write here: the outbox owns the retry and
     * the uncertain-write recovery, and a comment that fails must never undo a registration that
     * succeeded.
     */
    private void comment(String id, WorkItemEvent item, BuildDefaults.Defaults setup, String head) {
        if (!sources.get(item.sourceId()).map(source -> sources.client(source).capabilities()
                .contains(WorkSource.Capability.COMMENT)).orElse(false)) return;
        WorkItemEvent prepared = store.load(id);
        if (prepared == null || prepared.preparation() == null) return;
        String text = "Prepared by the factory from this ticket.\n\n"
                + "- Specification digest: `" + prepared.preparation().specification().sha256() + "`\n"
                + "- Plan: one step, built from " + setup.harness() + " on " + setup.model() + "\n"
                + "- Starts from `" + setup.baseBranch() + "` at `" + head + "`\n\n"
                + "Editing this ticket does not change what was prepared; prepare it again to pick up an edit.";
        try { effects.enqueue(UUID.randomUUID(), id, store.history(id).size(), WorkSourceEffects.Kind.COMMENT, text); }
        catch (RuntimeException failure) {
            // The preparation stands. A missing comment is worth a log line, not an undone registration.
            LOG.warnf("work item %s was prepared but its tracker comment could not be queued (%s)",
                    id, failure.getClass().getSimpleName());
        }
    }

    /** Records why this generation is still unprepared, and when to try again. */
    private void record(String id, String reason) {
        WorkItemEvent item = store.load(id);
        if (item == null) return;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                INSERT INTO work_item_preparation_attempt(work_item_id,generation,reason,retry_after)
                VALUES (?,?,?,now()+?::interval)
                ON CONFLICT (work_item_id,generation) DO UPDATE
                   SET reason=excluded.reason, attempts=work_item_preparation_attempt.attempts+1,
                       last_at=now(), retry_after=now()+(LEAST(?::bigint,
                           ?::bigint*POWER(2,LEAST(work_item_preparation_attempt.attempts,10))))*interval '1 second'
                """)) {
            ps.setString(1, id); ps.setLong(2, item.generation()); ps.setString(3, reason);
            ps.setString(4, FIRST_BACKOFF.toSeconds() + " seconds");
            ps.setLong(5, MAX_BACKOFF.toSeconds());
            ps.setLong(6, FIRST_BACKOFF.toSeconds());
            ps.executeUpdate();
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }

    /** A prepared generation has nothing left to report. */
    private void clear(String id, long generation) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM work_item_preparation_attempt WHERE work_item_id=? AND generation=?")) {
            ps.setString(1, id); ps.setLong(2, generation); ps.executeUpdate();
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }

    /** What the screens show beside an item that the system has not managed to prepare. */
    public record Health(String reason, int attempts, Instant lastAt, Instant retryAfter) {}

    public java.util.Optional<Health> health(String id, long generation) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT reason,attempts,last_at,retry_after FROM work_item_preparation_attempt WHERE work_item_id=? AND generation=?")) {
            ps.setString(1, id); ps.setLong(2, generation);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return java.util.Optional.empty();
                return java.util.Optional.of(new Health(rs.getString(1), rs.getInt(2),
                        rs.getTimestamp(3).toInstant(), rs.getTimestamp(4).toInstant()));
            }
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }
}
