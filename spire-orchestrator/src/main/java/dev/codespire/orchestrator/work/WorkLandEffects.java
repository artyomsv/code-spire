package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.port.PullRequestLander;
import dev.codespire.encryption.EncryptionService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * The {@code work_land_effect} rows the land sweep drains (M4 land, spec §7.3). Every move is guarded by the state the
 * caller read, so a stale pass never rewinds another's progress. The decisions about what a row means live in
 * {@link WorkLandSweep}; this class only reads and writes rows.
 */
@ApplicationScoped
public class WorkLandEffects {
    /** The states the sweep still drains; done, handed, closed, refused and fixing are another owner's or final. */
    static final Set<String> LIVE = Set.of("pending", "checking", "waiting_checks", "merging", "uncertain", "merged", "cleaning");
    /** No merge has been sent from these, so a row stuck in one can be handed to the operator, or stopped by them. */
    static final Set<String> BEFORE_MERGE = Set.of("pending", "checking", "waiting_checks");

    @Inject DataSource dataSource;
    @Inject ObjectMapper mapper;
    @Inject EncryptionService encryption;

    record Effect(UUID id, String item, long generation, long pullRequest, String approvedHead, String state, String mergeCommit,
                  String mergedBy, String method, String mergedHead, int cleanupTries, Instant waitingSince, Instant failingSince) {
        Effect at(String next) {
            return new Effect(id, item, generation, pullRequest, approvedHead, next, mergeCommit, mergedBy, method, mergedHead, cleanupTries,
                    waitingSince, failingSince);
        }
    }

    /** What the operator reads: GitHub's own message and the check summary. Both may quote source, so encrypted. */
    public record Detail(String message, PullRequestLander.CheckSummary checks) {}

    /** What a merged row holds: the merge commit, who merged, the method and the head that was merged. */
    record Merge(String commit, String mergedBy, String method, String head) {}

    /** The oldest live rows due for a pass at {@code now}, at most twenty. */
    List<UUID> due(Instant now) {
        List<UUID> ids = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT effect_id FROM work_land_effect WHERE state = ANY(?) AND (next_check_at IS NULL OR next_check_at <= ?)"
                        + " ORDER BY created_at LIMIT 20")) {
            ps.setArray(1, c.createArrayOf("text", LIVE.toArray()));
            ps.setTimestamp(2, Timestamp.from(now));
            try (ResultSet rs = ps.executeQuery()) { while (rs.next()) ids.add(rs.getObject(1, UUID.class)); }
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
        return ids;
    }

    Effect read(UUID id) {
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

    /** A state change; {@code detail} is kept when null. */
    void move(Effect effect, String state, String reason, Detail detail) {
        execute("""
                UPDATE work_land_effect SET state=?,reason=?,detail=COALESCE(?,detail),next_check_at=NULL,failing_since=NULL,updated_at=now()
                WHERE effect_id=? AND state=?
                """, state, reason, detail == null ? null : encode(effect.id(), detail), effect.id(), effect.state());
    }

    /** Why the row did not move this pass; the next pass tries again. */
    void note(Effect effect, String reason) {
        execute("UPDATE work_land_effect SET reason=?,updated_at=now() WHERE effect_id=? AND state=?", reason, effect.id(), effect.state());
    }

    /** Kept before a fix is requested, so the operator reads what GitHub said whichever way the decider goes. */
    void keepDetail(Effect effect, Detail detail) {
        execute("UPDATE work_land_effect SET detail=?,updated_at=now() WHERE effect_id=? AND state=?",
                encode(effect.id(), detail), effect.id(), effect.state());
    }

    /** A row that did not move this pass and is read again at {@code next}; {@code since} is when it started failing. */
    void backOff(Effect effect, String reason, Instant since, Instant next) {
        execute("UPDATE work_land_effect SET reason=?,failing_since=?,next_check_at=?,updated_at=now() WHERE effect_id=? AND state=?",
                reason, Timestamp.from(since), Timestamp.from(next), effect.id(), effect.state());
    }

    void waitForChecks(Effect effect, Instant since, Instant next, Detail detail) {
        execute("""
                UPDATE work_land_effect SET state='waiting_checks',reason=NULL,waiting_since=?,next_check_at=?,detail=?,failing_since=NULL,updated_at=now()
                WHERE effect_id=? AND state=?
                """, Timestamp.from(since), Timestamp.from(next), encode(effect.id(), detail), effect.id(), effect.state());
    }

    /**
     * Commits {@code uncertain} with the method sent, on the caller's connection and under its item lock, so a
     * recovery on a later pass records what was sent and not the setting saved then.
     */
    static boolean claimMerge(Connection c, Effect effect, String method) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE work_land_effect SET state='uncertain',reason=NULL,method=?,next_check_at=NULL,failing_since=NULL,updated_at=now() WHERE effect_id=? AND state=?")) {
            ps.setString(1, method); ps.setObject(2, effect.id()); ps.setString(3, effect.state());
            return ps.executeUpdate() == 1;
        }
    }

    boolean markMerged(Effect effect, Merge merge) {
        return execute("""
                UPDATE work_land_effect SET state='merged',reason=NULL,merge_commit=?,merged_by=?,method=?,merged_head=?,next_check_at=NULL,failing_since=NULL,updated_at=now()
                WHERE effect_id=? AND state=?
                """, merge.commit(), merge.mergedBy(), merge.method(), merge.head(), effect.id(), effect.state()) == 1;
    }

    /** One more failed branch delete; the row is done once {@code exhausted}, with the note the operator reads. */
    void cleanupFailed(Effect effect, int tries, boolean exhausted) {
        execute("UPDATE work_land_effect SET cleanup_tries=?,state=?,note=?,updated_at=now() WHERE effect_id=? AND state='cleaning'",
                tries, exhausted ? "done" : "cleaning", exhausted ? "branch_delete_failed" : null, effect.id());
    }

    /**
     * Refuses a row that has sent no merge, in the caller's transaction (Stop land, review of the branch).
     *
     * @return whether the row was before its merge call and is now refused
     */
    static boolean refuseBeforeMerge(Connection c, UUID effect, String reason) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(
                "UPDATE work_land_effect SET state='refused',reason=?,next_check_at=NULL,updated_at=now() WHERE effect_id=? AND state = ANY(?)")) {
            ps.setString(1, reason); ps.setObject(2, effect); ps.setArray(3, c.createArrayOf("text", BEFORE_MERGE.toArray()));
            return ps.executeUpdate() == 1;
        }
    }

    /** Whether the latest land row of this generation has sent no merge yet, so Stop land may end it. */
    boolean beforeMerge(String item, long generation) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT state FROM work_land_effect WHERE work_item_id=? AND generation=? ORDER BY created_at DESC,effect_id LIMIT 1")) {
            ps.setString(1, item); ps.setLong(2, generation);
            try (ResultSet rs = ps.executeQuery()) { return rs.next() && BEFORE_MERGE.contains(rs.getString(1)); }
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }

    byte[] encode(UUID effect, Detail detail) {
        try { return encryption.encrypt(mapper.writeValueAsBytes(detail), "work-land-detail:" + effect); }
        catch (IOException failure) { throw new IllegalStateException("Cannot encode the land detail of " + effect, failure); }
    }

    /** The operator's view of a row's detail; null when it holds none. */
    Detail detail(UUID effect, byte[] stored) {
        if (stored == null) return null;
        try { return mapper.readValue(encryption.decrypt(stored, "work-land-detail:" + effect), Detail.class); }
        catch (IOException failure) { throw new IllegalStateException("Cannot read the land detail of " + effect, failure); }
    }

    private int execute(String sql, Object... values) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            for (int index = 0; index < values.length; index++) ps.setObject(index + 1, values[index]);
            return ps.executeUpdate();
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
