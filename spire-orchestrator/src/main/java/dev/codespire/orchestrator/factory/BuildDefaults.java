package dev.codespire.orchestrator.factory;

import dev.codespire.orchestrator.llm.LlmModelRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import javax.sql.DataSource;

/**
 * What a repository builds with, so a person does not type it per work item (M3.5 part B).
 *
 * <p>Every value is checked against what this deployment can actually run: a harness without an agent
 * image and a model the catalog does not offer are both refused at dispatch today — after the operator
 * has typed them, approved a plan and waited. Refusing at save time moves that answer to where it can
 * be acted on, and the dispatch check stays, because a catalog can change in between.
 */
@ApplicationScoped
public class BuildDefaults {
    @Inject DataSource dataSource;
    @Inject FactoryConfig config;
    @Inject LlmModelRegistry models;

    /**
     * @param revision 0 when the repository has none yet, with null coordinates — "not set" is a state
     *     the setup screen has to render, and a zero-revision row is the same answer a save rejects
     */
    public record Defaults(long revision, String baseBranch, String harness, String model,
                           String updatedBy, Instant updatedAt) {
        public static Defaults none() { return new Defaults(0, null, null, null, null, null); }
        public boolean set() { return revision > 0; }
    }
    public record Input(long expectedRevision, String baseBranch, String harness, String model) {}

    /** A refusal that names its rule, so the screen can say what to change rather than "400". */
    public static final class Refused extends RuntimeException {
        private final String reason;
        Refused(String reason) { super(reason); this.reason = reason; }
        public String reason() { return reason; }
    }

    public Defaults get(UUID repository) {
        try (Connection c = dataSource.getConnection()) { return get(c, repository, false); }
        catch (SQLException failure) { throw database(failure); }
    }

    /** Package-private with an explicit lock flag: part C reads these under the item's own lock. */
    Defaults get(Connection c, UUID repository, boolean lock) throws SQLException {
        String sql = "SELECT revision,base_branch,harness,model,updated_by,updated_at"
                + " FROM repository_build_defaults WHERE repository_id=?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, repository);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Defaults.none();
                return new Defaults(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getTimestamp(6).toInstant());
            }
        }
    }

    /**
     * Transactional for the same reason {@code WorkPolicyRegistry.save} is: without one, every
     * statement commits on its own and the two {@code FOR UPDATE} locks below are released the moment
     * they are taken. Two operators could then both read revision N and both write, and the second
     * would silently overwrite the first — the exact loss the expected revision exists to prevent.
     */
    @Transactional
    public Defaults save(UUID repository, Input input, String actor) {
        if (input == null) throw new Refused("build_defaults_required");
        if (actor == null || actor.isBlank()) throw new Refused("operator_identity_required");
        String branch = strip(input.baseBranch()), harness = strip(input.harness()), model = strip(input.model());
        if (branch == null) throw new Refused("base_branch_blank");
        // The branch, the harness and the model are checked against the rules the DISPATCH applies, not
        // against a second opinion written here. A value that passes here and fails there would put the
        // refusal back where this slice is taking it away from: after an approval, mid-item.
        if (!DispatchRequestParser.isRefName(branch)) throw new Refused("base_branch_invalid");
        // An exact key, no case folding, as at DispatchRequestParser.java:73.
        if (harness == null || !config.agentImage().containsKey(harness))
            throw new Refused("harness_unconfigured");
        if (model == null || !DispatchRequestParser.isModelName(model)) throw new Refused("model_name_invalid");
        // Enabled AND priceable. Dispatch prices a model by name and does not read `enabled`
        // (LlmModelPricer.pricingFor), so refusing a disabled model here is stricter than dispatch on
        // purpose: a model an operator switched off is not one a repository should silently keep using.
        // The reverse — a model disabled AFTER this save — is caught by part D, which adds the harness's
        // own reported types to the dispatch check.
        if (models.list().stream().noneMatch(known -> known.enabled() && known.name().equals(model)))
            throw new Refused("model_unknown");
        if (!models.isPriceable(model)) throw new Refused("model_pricing_unavailable");
        try (Connection c = dataSource.getConnection()) {
            // Same lock order as the policy save: the repository row first, so a save cannot interleave
            // with a repository or account edit that decides whether these coordinates can run at all.
            try (PreparedStatement ps = c.prepareStatement("SELECT id FROM repository WHERE id=? FOR UPDATE")) {
                ps.setObject(1, repository);
                try (ResultSet rs = ps.executeQuery()) { if (!rs.next()) throw new Refused("repository_unknown"); }
            }
            if (get(c, repository, true).revision() != input.expectedRevision())
                throw new Refused("build_defaults_changed");
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO repository_build_defaults(repository_id,base_branch,harness,model,updated_by)
                    VALUES (?,?,?,?,?)
                    ON CONFLICT (repository_id) DO UPDATE SET base_branch=excluded.base_branch,harness=excluded.harness,
                        model=excluded.model,updated_by=excluded.updated_by,updated_at=now(),
                        revision=repository_build_defaults.revision+1
                    """)) {
                ps.setObject(1, repository); ps.setString(2, branch); ps.setString(3, harness);
                ps.setString(4, model); ps.setString(5, actor);
                ps.executeUpdate();
            }
            return get(c, repository, false);
        } catch (SQLException failure) { throw database(failure); }
    }

    private static IllegalStateException database(SQLException failure) {
        return new IllegalStateException("The repository build defaults could not be read or saved", failure);
    }

    /** strip(), not trim(): the dispatch parser strips, and two whitespace rules is one rule too many. */
    private static String strip(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
