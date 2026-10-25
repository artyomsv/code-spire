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

    /** The ticket, the setup and the base head read for a dispatch, or the refusal that stops it. */
    record Reads(WorkTicket ticket, BuildDefaults.Defaults setup, String head, String refusal) {
        static Reads refused(String reason) { return new Reads(null, null, null, reason); }
    }

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

    /** The source and policy were observed by the caller; a source or ticket that cannot be read is refused by name. */
    Reads read(WorkItemEvent item, WorkItemTransitions.Observation observed) {
        if (observed.evidence().failure() != null) return Reads.refused(observed.evidence().failure());
        BuildDefaults.Defaults setup = defaults.get(item.repositoryId());
        String local = sweep.localRefusal(setup);
        if (local != null) return Reads.refused(local);
        if (!(sources.client(observed.source()).fetch(item.issue()) instanceof WorkSource.Fetch.Found found))
            return Reads.refused("artifacts_unavailable");
        try { return new Reads(found.ticket(), setup, sweep.head(item, setup.baseBranch()), null); }
        catch (RuntimeException unavailable) { return Reads.refused("branch_head_unconfirmed"); }
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
