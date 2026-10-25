package dev.codespire.orchestrator.work;

import dev.codespire.contract.work.WorkDraftBinding;
import dev.codespire.contract.work.WorkItemEvent;
import dev.codespire.contract.work.WorkPreparation;
import dev.codespire.encryption.EncryptionService;
import dev.codespire.orchestrator.factory.BuildDefaults;
import dev.codespire.worksource.WorkSource;
import dev.codespire.worksource.WorkTicket;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;

/**
 * What {@link WorkPrepareDispatcher} reads before its claim (M4 slice 3): the effect row, and the remote facts a
 * specification run is built from, the ticket and the head of the setup's base branch. Remote reads are made outside
 * any lock, as the copy sweep makes them; the claim compares what they were read against under the lock.
 */
@ApplicationScoped
public class WorkPrepareReads {
    @Inject DataSource dataSource;
    @Inject EncryptionService encryption;
    @Inject WorkSourceRegistry sources;
    @Inject BuildDefaults defaults;
    @Inject WorkPreparationSweep sweep;

    /**
     * One draft attempt's effect row, decrypted.
     *
     * @param retryError why the attempt this one retries was refused, or null on a first attempt
     * @param note the revision note copied for this attempt (decision 8), or null
     */
    record Effect(UUID attempt, String itemId, WorkDraftBinding.Kind kind, String retryError, String note) {}

    /**
     * The ticket, the setup and the base head read for a dispatch; or the refusal that stops it; or the deferral of a
     * remote read that did not answer, which the dispatcher asks again after a backoff instead of stopping the item.
     */
    record Reads(WorkTicket ticket, BuildDefaults.Defaults setup, String head, String refusal, String deferral) {
        static Reads refused(String reason) { return new Reads(null, null, null, reason, null); }

        static Reads deferred(String reason) { return new Reads(null, null, null, null, reason); }
    }

    /** Evidence failures that a later read may not repeat: an outage, or labels that moved while they were read. */
    private static final Set<String> TRANSIENT_EVIDENCE = Set.of("tracker_unavailable", "tracker_changed_during_read");
    /** What {@link WorkPreparationSweep#head} names for a repository it cannot read at all; configuration settles it. */
    private static final Set<String> NAMED_HEAD_REFUSALS = Set.of("repository_account_missing", "source_unavailable");

    static String errorAad(UUID attempt) { return "work-prepare-error:" + attempt; }

    static String noteAad(UUID attempt) { return "work-prepare-note:" + attempt; }

    Effect effect(UUID attempt) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("SELECT work_item_id,kind,validation_error,note FROM work_prepare_effect WHERE attempt_id=?")) {
            ps.setObject(1, attempt);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return null;
                return new Effect(attempt, rs.getString(1), WorkDraftBinding.Kind.valueOf(rs.getString(2)),
                        decrypt(rs.getBytes(3), errorAad(attempt)), decrypt(rs.getBytes(4), noteAad(attempt)));
            }
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }

    /**
     * The source and policy were observed by the caller. What the setup or a confirmed answer settles is refused by
     * name; a tracker or forge that did not answer is deferred, as the copy sweep retries the same reads, because one
     * network blip before anything is bought must not end the item.
     */
    Reads read(WorkItemEvent item, WorkItemTransitions.Observation observed) {
        String evidence = observed.evidence().failure();
        if (evidence != null) return TRANSIENT_EVIDENCE.contains(evidence) ? Reads.deferred(evidence) : Reads.refused(evidence);
        BuildDefaults.Defaults setup = defaults.get(item.repositoryId());
        String local = sweep.localRefusal(setup);
        if (local != null) return Reads.refused(local);
        WorkSource.Fetch fetched;
        try { fetched = sources.client(observed.source()).fetch(item.issue()); }
        catch (RuntimeException unavailable) { return Reads.deferred("artifacts_unavailable"); }
        if (fetched instanceof WorkSource.Fetch.Unavailable) return Reads.deferred("artifacts_unavailable");
        if (!(fetched instanceof WorkSource.Fetch.Found found)) return Reads.refused("artifacts_unavailable");
        try { return new Reads(found.ticket(), setup, sweep.head(item, setup.baseBranch()), null, null); }
        catch (RuntimeException unavailable) { return headRefusal(unavailable); }
    }

    /** A named configuration fault keeps its name; anything else, a forge's answer included, is a read to try again. */
    private static Reads headRefusal(RuntimeException unavailable) {
        String named = unavailable instanceof IllegalStateException ? unavailable.getMessage() : null;
        if (named != null && NAMED_HEAD_REFUSALS.contains(named)) return Reads.refused(named);
        return Reads.deferred("branch_head_unconfirmed");
    }

    /**
     * The draft run's provisional digest (A4): the setup revision, the kind and the ticket as it was read. No preparation
     * binding exists before both drafts are approved, so the run is bound to what it was built from instead.
     */
    static String provisionalDigest(long setupRevision, WorkTicket ticket, WorkDraftBinding.Kind kind) {
        String labels = ticket.currentLabels().stream().sorted().collect(Collectors.joining("\n"));
        String ticketDigest = WorkPreparation.digest(String.valueOf(ticket.title()) + "\n" + String.valueOf(ticket.body()) + "\n" + labels);
        return WorkPreparation.digest("draft-v1\n" + setupRevision + "\n" + kind + "\n" + ticketDigest);
    }

    private String decrypt(byte[] bytes, String aad) {
        return bytes == null ? null : new String(encryption.decrypt(bytes, aad), StandardCharsets.UTF_8);
    }
}
