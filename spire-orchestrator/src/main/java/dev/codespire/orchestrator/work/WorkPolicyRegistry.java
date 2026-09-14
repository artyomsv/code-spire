package dev.codespire.orchestrator.work;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.codespire.contract.work.WorkPolicy;
import dev.codespire.contract.work.WorkPolicyLimits;
import dev.codespire.workspace.ProtectedPaths;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.*;
import java.util.*;
import javax.sql.DataSource;

@ApplicationScoped
public class WorkPolicyRegistry {
    @Inject DataSource dataSource;
    @Inject ObjectMapper mapper;
    public record Policy(long revision, WorkPolicy.Profile ceiling, Map<String, WorkPolicy.Profile> mappings) {
        public Policy { mappings = Map.copyOf(mappings); }
    }
    public record Pin(UUID id, long version) {}
    public record Input(long revision, Pin ceiling, Map<String, Pin> mappings) {}

    public Policy get(UUID repository) {
        try (Connection c = dataSource.getConnection()) { return get(c, repository, false); }
        catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }
    Policy get(Connection c, UUID repository, boolean lock) throws SQLException {
        String sql = lock ? "SELECT revision,ceiling_id,ceiling_version FROM work_repository_policy WHERE repository_id=? FOR UPDATE"
                : "SELECT revision,ceiling_id,ceiling_version FROM work_repository_policy WHERE repository_id=?";
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, repository);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return new Policy(0, null, Map.of());
                WorkPolicy.Profile ceiling = profile(c, new Pin(rs.getObject(2, UUID.class), rs.getLong(3)));
                Map<String, WorkPolicy.Profile> mappings = new HashMap<>();
                try (PreparedStatement labels = c.prepareStatement("SELECT label,profile_id,profile_version FROM work_label_mapping WHERE repository_id=?")) {
                    labels.setObject(1, repository);
                    try (ResultSet rows = labels.executeQuery()) {
                        while (rows.next()) mappings.put(rows.getString(1), profile(c, new Pin(rows.getObject(2, UUID.class), rows.getLong(3))));
                    }
                }
                return new Policy(rs.getLong(1), ceiling, mappings);
            }
        }
    }

    public List<WorkPolicy.Profile> profiles() {
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement("""
                SELECT v.definition FROM autonomy_profile_version v JOIN autonomy_profile p ON p.id=v.profile_id
                WHERE NOT p.deleted ORDER BY p.precedence,v.version
                """); ResultSet rs = ps.executeQuery()) {
            List<WorkPolicy.Profile> result = new ArrayList<>();
            while (rs.next()) result.add(decode(rs.getString(1)));
            return result;
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }

    @Transactional
    public WorkPolicy.Profile createVersion(WorkPolicy.Profile profile) {
        dev.codespire.workspace.PathGlob.compileAll(List.copyOf(profile.limits().protectedPaths()));
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO autonomy_profile(id,name,precedence) VALUES (?,?,?) ON CONFLICT (id) DO NOTHING
                    """)) {
                ps.setObject(1, profile.id()); ps.setString(2, profile.name()); ps.setInt(3, profile.precedence()); ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("SELECT name,precedence,deleted FROM autonomy_profile WHERE id=? FOR UPDATE")) {
                ps.setObject(1, profile.id());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next() || rs.getBoolean(3) || !profile.name().equals(rs.getString(1)) || profile.precedence() != rs.getInt(2))
                        throw new IllegalArgumentException("Profile name and precedence must match its identity");
                }
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO autonomy_profile_version(profile_id,version,definition) VALUES (?,?,?::jsonb)")) {
                ps.setObject(1, profile.id()); ps.setLong(2, profile.version()); ps.setString(3, encode(profile)); ps.executeUpdate();
            }
            return profile;
        } catch (SQLException failure) {
            if("23505".equals(failure.getSQLState()))throw new IllegalArgumentException("Profile name, precedence or version already exists");
            throw WorkSourceRegistry.database(failure);
        }
    }

    @Transactional
    public Policy save(UUID repository, Input input) {
        if (input == null || input.ceiling() == null || input.mappings() == null) throw new IllegalArgumentException("Ceiling and mappings are required");
        try (Connection c = dataSource.getConnection()) {
            // Serialize even the first policy insert with intake and repository/source edits.
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM repository WHERE id=? FOR UPDATE")) {
                ps.setObject(1, repository);
                try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new IllegalArgumentException("Repository not registered"); }
            }
            if (get(c, repository, true).revision() != input.revision()) throw new IllegalArgumentException("Policy changed; reload it");
            profile(c, input.ceiling());
            for (Map.Entry<String, Pin> mapping : input.mappings().entrySet()) {
                if (mapping.getKey() == null || mapping.getKey().isBlank()) throw new IllegalArgumentException("Label cannot be blank");
                profile(c, mapping.getValue());
            }
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO work_repository_policy(repository_id,ceiling_id,ceiling_version) VALUES (?,?,?)
                    ON CONFLICT (repository_id) DO UPDATE SET ceiling_id=excluded.ceiling_id,ceiling_version=excluded.ceiling_version,
                    revision=work_repository_policy.revision+1
                    """)) {
                ps.setObject(1, repository); ps.setObject(2, input.ceiling().id()); ps.setLong(3, input.ceiling().version()); ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("DELETE FROM work_label_mapping WHERE repository_id=?")) {
                ps.setObject(1, repository); ps.executeUpdate();
            }
            try (PreparedStatement ps = c.prepareStatement("INSERT INTO work_label_mapping(repository_id,label,profile_id,profile_version) VALUES (?,?,?,?)")) {
                for (Map.Entry<String, Pin> mapping : input.mappings().entrySet()) {
                    ps.setObject(1, repository); ps.setString(2, mapping.getKey()); ps.setObject(3, mapping.getValue().id());
                    ps.setLong(4, mapping.getValue().version()); ps.addBatch();
                }
                ps.executeBatch();
            }
            return get(c, repository, false);
        } catch (SQLException failure) { throw WorkSourceRegistry.database(failure); }
    }
    private WorkPolicy.Profile profile(Connection c, Pin pin) throws SQLException {
        if (pin == null) throw new IllegalArgumentException("A profile version is required");
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT v.definition FROM autonomy_profile_version v JOIN autonomy_profile p ON p.id=v.profile_id
                WHERE p.id=? AND v.version=? AND NOT p.deleted
                """)) {
            ps.setObject(1, pin.id()); ps.setLong(2, pin.version());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) throw new IllegalArgumentException("Profile version is missing or deleted");
                return decode(rs.getString(1));
            }
        }
    }
    private WorkPolicy.Profile decode(String value) {
        try {
            WorkPolicy.Profile profile = mapper.readValue(value, WorkPolicy.Profile.class);
            return new WorkPolicy.Profile(profile.id(), profile.name(), profile.version(), profile.precedence(), profile.modes(),
                    WorkPolicyLimits.meet(List.of(profile.limits()), Set.copyOf(ProtectedPaths.CI_FLOOR)));
        }
        catch (java.io.IOException failure) { throw new IllegalStateException("Invalid stored work policy", failure); }
    }
    private String encode(WorkPolicy.Profile value) {
        try { return mapper.writeValueAsString(value); }
        catch (java.io.IOException failure) { throw new IllegalStateException("Invalid work policy", failure); }
    }
}
