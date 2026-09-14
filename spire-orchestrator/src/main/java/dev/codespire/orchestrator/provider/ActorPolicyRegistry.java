package dev.codespire.orchestrator.provider;

import dev.codespire.contract.scm.ResolvedActor;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import javax.sql.DataSource;

@ApplicationScoped
public class ActorPolicyRegistry {
    @Inject DataSource dataSource;

    public record Policy(long revision, List<ActorDisplay> actors) {}

    public Policy account(UUID account) {
        try (Connection c = dataSource.getConnection()) {
            long revision = accountRevision(c, account, false);
            try (PreparedStatement ps = c.prepareStatement("SELECT author,observed_handle,display_name,resolved_at,refresh_failed FROM provider_author WHERE provider_id=? ORDER BY author")) {
                ps.setObject(1, account);
                List<ActorDisplay> actors = new ArrayList<>();
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) actors.add(display(rs, "author", "ALLOW", revision));
                }
                return new Policy(revision, actors);
            }
        } catch (SQLException failure) { throw database(failure); }
    }

    public List<ActorDisplay> repository(UUID repository) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "SELECT actor_id,effect,observed_handle,display_name,resolved_at,refresh_failed,revision FROM repository_fix_actor WHERE repository_id=? ORDER BY actor_id")) {
            ps.setObject(1, repository);
            List<ActorDisplay> actors = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) actors.add(display(rs, "actor_id", rs.getString("effect"), rs.getLong("revision")));
            }
            return actors;
        } catch (SQLException failure) { throw database(failure); }
    }

    @Transactional
    public void saveAccount(UUID account, ResolvedActor actor, long expectedRevision) {
        try (Connection c = dataSource.getConnection()) {
            if (accountRevision(c, account, true) != expectedRevision) throw new Conflict();
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO provider_author(provider_id,author,observed_handle,display_name,resolved_at)
                    VALUES (?,?,?,?,now()) ON CONFLICT (provider_id,author) DO UPDATE
                    SET observed_handle=excluded.observed_handle,display_name=excluded.display_name,resolved_at=excluded.resolved_at,refresh_failed=false
                    """)) {
                ps.setObject(1, account); ps.setString(2, actor.providerUserId());
                ps.setString(3, actor.handle()); ps.setString(4, actor.displayName()); ps.executeUpdate();
            }
            bumpAccount(c, account);
        } catch (SQLException failure) { throw database(failure); }
    }

    @Transactional
    public void deleteAccount(UUID account, String id, long expectedRevision) {
        try (Connection c = dataSource.getConnection()) {
            if (accountRevision(c, account, true) != expectedRevision) throw new Conflict();
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM provider_author WHERE provider_id=? AND author=?")) {
                ps.setObject(1, account); ps.setString(2, id); ps.executeUpdate();
            }
            bumpAccount(c, account);
        } catch (SQLException failure) { throw database(failure); }
    }

    @Transactional
    public void saveRepository(UUID repository, UUID account, long repositoryRevision, ResolvedActor actor, String effect, long expectedRevision) {
        try (Connection c = dataSource.getConnection()) {
            // Lock the same repository row as binding edits; an in-flight resolution cannot cross a rebind.
            try (PreparedStatement ps = c.prepareStatement("""
                    SELECT r.revision,a.account_id FROM repository r JOIN repository_account a
                    ON a.repository_id=r.id AND a.role='REVIEWER' WHERE r.id=? FOR UPDATE OF r
                    """)) {
                ps.setObject(1, repository);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getLong(1) != repositoryRevision || !account.equals(rs.getObject(2, UUID.class))) throw new Conflict();
                }
            }
            long current = 0;
            try (PreparedStatement ps = c.prepareStatement("SELECT revision FROM repository_fix_actor WHERE repository_id=? AND actor_id=?")) {
                ps.setObject(1, repository); ps.setString(2, actor.providerUserId());
                try (ResultSet rs = ps.executeQuery()) { if (rs.next()) current = rs.getLong(1); }
            }
            if (current != expectedRevision) throw new Conflict();
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO repository_fix_actor(repository_id,actor_id,effect,observed_handle,display_name,resolved_at)
                    VALUES (?,?,?,?,?,now()) ON CONFLICT (repository_id,actor_id) DO UPDATE
                    SET effect=excluded.effect,observed_handle=excluded.observed_handle,display_name=excluded.display_name,
                        resolved_at=excluded.resolved_at,refresh_failed=false,revision=nextval('repository_fix_actor_revision_seq')
                    """)) {
                ps.setObject(1, repository); ps.setString(2, actor.providerUserId()); ps.setString(3, effect);
                ps.setString(4, actor.handle()); ps.setString(5, actor.displayName()); ps.executeUpdate();
            }
        } catch (SQLException failure) { throw database(failure); }
    }

    @Transactional
    public void deleteRepository(UUID repository, String id, long expectedRevision) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "DELETE FROM repository_fix_actor WHERE repository_id=? AND actor_id=? AND revision=?")) {
            ps.setObject(1, repository); ps.setString(2, id); ps.setLong(3, expectedRevision);
            if (ps.executeUpdate() != 1) throw new Conflict();
        } catch (SQLException failure) { throw database(failure); }
    }

    @Transactional
    public void refreshAccount(UUID account, ResolvedActor actor) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE provider_author SET observed_handle=?,display_name=?,resolved_at=now(),refresh_failed=false WHERE provider_id=? AND author=?")) {
            ps.setString(1, actor.handle()); ps.setString(2, actor.displayName());
            ps.setObject(3, account); ps.setString(4, actor.providerUserId()); ps.executeUpdate();
        } catch (SQLException failure) { throw database(failure); }
    }

    @Transactional
    public void refreshRepository(UUID repository, ResolvedActor actor) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(
                "UPDATE repository_fix_actor SET observed_handle=?,display_name=?,resolved_at=now(),refresh_failed=false WHERE repository_id=? AND actor_id=?")) {
            ps.setString(1, actor.handle()); ps.setString(2, actor.displayName());
            ps.setObject(3, repository); ps.setString(4, actor.providerUserId()); ps.executeUpdate();
        } catch (SQLException failure) { throw database(failure); }
    }

    private static ActorDisplay display(ResultSet rs, String id, String effect, long revision) throws SQLException {
        Instant at = rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant();
        return new ActorDisplay(rs.getString(id), rs.getString("observed_handle"), rs.getString("display_name"), at,
                rs.getBoolean("refresh_failed") || at == null || at.isBefore(Instant.now().minusSeconds(86400)), effect, revision);
    }

    @Transactional
    public void failedAccountRefresh(UUID account, String actor) {
        failedRefresh("UPDATE provider_author SET refresh_failed=true WHERE provider_id=? AND author=?", account, actor);
    }
    @Transactional
    public void failedRepositoryRefresh(UUID repository, String actor) {
        failedRefresh("UPDATE repository_fix_actor SET refresh_failed=true WHERE repository_id=? AND actor_id=?", repository, actor);
    }
    private void failedRefresh(String sql, UUID owner, String actor) {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, owner); ps.setString(2, actor); ps.executeUpdate();
        } catch (SQLException failure) { throw database(failure); }
    }

    private static long accountRevision(Connection c, UUID account, boolean lock) throws SQLException {
        String sql = lock ? "SELECT actor_policy_revision FROM scm_provider WHERE id=? FOR UPDATE"
                : "SELECT actor_policy_revision FROM scm_provider WHERE id=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, account);
            try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new Conflict(); return rs.getLong(1); }
        }
    }
    private static void bumpAccount(Connection c, UUID account) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("UPDATE scm_provider SET actor_policy_revision=actor_policy_revision+1 WHERE id=?")) {
            ps.setObject(1, account); ps.executeUpdate();
        }
    }
    public static final class Conflict extends RuntimeException { public Conflict() { super("People or account selection changed. Reload before saving."); } }
    private static RuntimeException database(SQLException failure) { return new IllegalStateException("Could not persist actor policy", failure); }
}
