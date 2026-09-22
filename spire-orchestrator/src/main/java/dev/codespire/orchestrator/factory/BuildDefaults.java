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
    @Inject dev.codespire.orchestrator.llm.LlmModelPricer pricer;
    @Inject HarnessCatalogues catalogues;

    /**
     * @param revision 0 when the repository has none yet, with null coordinates — "not set" is a state
     *     the setup screen has to render, and a zero-revision row is the same answer a save rejects
     */
    /**
     * @param effort the thinking level, or null for the model's own default — a real choice, not a gap
     */
    public record Defaults(long revision, String baseBranch, String harness, String model, String effort,
                           String updatedBy, Instant updatedAt) {
        public static Defaults none() { return new Defaults(0, null, null, null, null, null, null); }
        public boolean set() { return revision > 0; }
    }

    /** @param effort null for the model's own default */
    public record Input(long expectedRevision, String baseBranch, String harness, String model, String effort) {
        /** Every caller written before thinking levels existed keeps the model's own default. */
        public Input(long expectedRevision, String baseBranch, String harness, String model) {
            this(expectedRevision, baseBranch, harness, model, null);
        }
    }

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

    /**
     * On the caller's connection, so the automatic preparation can compare the setup it composed from
     * against the saved one INSIDE the transaction that registers the result (M3.5 part C).
     */
    public Defaults get(Connection c, UUID repository, boolean lock) throws SQLException {
        String sql = "SELECT revision,base_branch,harness,model,effort,updated_by,updated_at"
                + " FROM repository_build_defaults WHERE repository_id=?" + (lock ? " FOR UPDATE" : "");
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setObject(1, repository);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return Defaults.none();
                return new Defaults(rs.getLong(1), rs.getString(2), rs.getString(3), rs.getString(4),
                        rs.getString(5), rs.getString(6), rs.getTimestamp(7).toInstant());
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
        String effort = strip(input.effort());
        if (branch == null) throw new Refused("base_branch_blank");
        // The branch, the harness and the model are checked against the rules the DISPATCH applies, not
        // against a second opinion written here. A value that passes here and fails there would put the
        // refusal back where this slice is taking it away from: after an approval, mid-item.
        if (!DispatchRequestParser.isRefName(branch)) throw new Refused("base_branch_invalid");
        // An exact key, no case folding, as at DispatchRequestParser.java:73.
        if (harness == null || !config.agentImage().containsKey(harness))
            throw new Refused("harness_unconfigured");
        if (model == null || !DispatchRequestParser.isModelName(model)) throw new Refused("model_name_invalid");
        checkAgainstTheHarness(harness, model, effort);
        // Exactly what the dispatch refuses, asked here: the model must be offered, and it must price
        // every token type this harness can report. A model disabled AFTER this save is refused at
        // dispatch too (WorkRunAssembly), so the two no longer disagree in either direction.
        if (models.list().stream().noneMatch(known -> known.enabled() && known.name().equals(model)))
            throw new Refused("model_unknown");
        var unpriced = pricer.unpricedTypes(model, harness);
        if (!unpriced.isEmpty()) throw new Refused("model_pricing_incomplete:"
                + unpriced.stream().map(Enum::name).collect(java.util.stream.Collectors.joining(",")));
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
                    INSERT INTO repository_build_defaults(repository_id,base_branch,harness,model,effort,updated_by)
                    VALUES (?,?,?,?,?,?)
                    ON CONFLICT (repository_id) DO UPDATE SET base_branch=excluded.base_branch,harness=excluded.harness,
                        model=excluded.model,effort=excluded.effort,updated_by=excluded.updated_by,updated_at=now(),
                        revision=repository_build_defaults.revision+1
                    """)) {
                ps.setObject(1, repository); ps.setString(2, branch); ps.setString(3, harness);
                ps.setString(4, model); ps.setString(5, effort); ps.setString(6, actor);
                ps.executeUpdate();
            }
            // Saving a setup is the repair for "this repository has no build setup", so the items that
            // were refused for it are made due again rather than waiting out a backoff they have
            // outlived. Same transaction: a save that rolls back must not leave items woken for nothing.
            try (PreparedStatement ps = c.prepareStatement("""
                    UPDATE work_item_preparation_attempt SET retry_after=now()
                     WHERE work_item_id IN (SELECT id FROM work_item WHERE repository_id=?)
                    """)) {
                ps.setObject(1, repository); ps.executeUpdate();
            }
            return get(c, repository, false);
        } catch (SQLException failure) { throw database(failure); }
    }

    /**
     * Whether the chosen harness can run this model, at this thinking level (M3.5 part M).
     *
     * <p><b>Only when the harness's own list is known.</b> When it is not — no answer from the run worker
     * yet, an image built without its catalogue, one that could not be read or reached — this does NOT
     * refuse, and that is a decision rather than an oversight. The check exists to stop an avoidable
     * wrong choice; when nothing can know which choice is wrong, refusing every save would lock the
     * operator out of the build setup altogether over a background answer that has not arrived, and a
     * development stack with no run worker would never be able to save one at all. The failure it lets
     * through is the one that existed before this check — a model the harness cannot run is refused when
     * the run starts — and the screen says the list could not be read, so the gap is visible.
     *
     * <p>A thinking level, though, IS refused when the list is unknown. There is nothing to check it
     * against, and a level the model does not offer would be passed to the vendor as it stands.
     */
    private void checkAgainstTheHarness(String harness, String model, String effort) {
        catalogues.refusal(harness, model, effort).ifPresent(reason -> { throw new Refused(reason); });
    }

    private static IllegalStateException database(SQLException failure) {
        return new IllegalStateException("The repository build defaults could not be read or saved", failure);
    }

    /** strip(), not trim(): the dispatch parser strips, and two whitespace rules is one rule too many. */
    private static String strip(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
