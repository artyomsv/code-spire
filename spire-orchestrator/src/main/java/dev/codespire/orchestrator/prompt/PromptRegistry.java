package dev.codespire.orchestrator.prompt;

import dev.codespire.contract.llm.PromptCatalog;
import dev.codespire.contract.llm.PromptKind;
import dev.codespire.contract.llm.PromptTemplate;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * CRUD over {@code prompt_template} (keyed on {@code (scope, kind)}: {@link PromptScope#GLOBAL} for
 * the deployment-wide row, else one row per repository) plus effective-template resolution:
 * most-specific-wins -- a repo row overrides the global row, which overrides the built-in
 * {@link PromptCatalog} default. No secrets, so no encryption (mirrors {@code LlmModelRegistry}).
 *
 * <p>A repo row replaces both {@code system} and {@code body} -- never a per-field merge. Merging
 * would mean an operator editing the global persona silently changed the effective prompt of every
 * repo that had overridden only the body.
 *
 * <p>The single-argument overloads are global-scope convenience delegates kept for existing callers
 * (the Settings -> Prompts global editor); nothing about their behaviour changed by this class
 * gaining scope.
 */
@ApplicationScoped
public class PromptRegistry {

    @Inject
    DataSource dataSource;

    public List<PromptView> list() {
        return list(PromptScope.GLOBAL);
    }

    /** The effective view of every kind at a scope -- what Settings -> Prompts renders. */
    public List<PromptView> list(String scope) {
        List<PromptView> out = new ArrayList<>();
        for (PromptKind kind : PromptKind.values()) {
            out.add(effective(kind, scope));
        }
        return out;
    }

    /** The effective view at global scope: the stored override if present, else the built-in default. */
    public PromptView effective(PromptKind kind) {
        return effective(kind, PromptScope.GLOBAL);
    }

    /**
     * The effective view for a kind at a scope: the repo row if present, else the global row, else
     * the built-in default. {@code baseKnown}/{@code defaultDrifted} describe whichever row was
     * actually used to resolve {@code system}/{@code body}, so the view stays internally consistent.
     */
    public PromptView effective(PromptKind kind, String scope) {
        Resolved resolved = resolvedRow(kind, scope);
        Optional<Row> row = resolved.row();
        PromptTemplate def = PromptCatalog.defaultTemplate(kind);
        String system = row.map(Row::system).orElse(def.system());
        String body = row.map(Row::body).orElse(def.body());
        Instant updatedAt = row.map(Row::updatedAt).orElse(null);
        Drift drift = driftOf(row, kind);
        return new PromptView(kind.slug(), scope, resolved.inheritedFrom(), row.isPresent(), system, body,
                updatedAt, PromptCatalog.palette(kind), PromptCatalog.lockedSystemSuffix(kind),
                drift.baseKnown(), drift.defaultDrifted(), def.system(), def.body(),
                drift.baseSystem(), drift.baseBody());
    }

    /** The stored override at global scope, or empty when the kind uses the default. */
    public Optional<PromptTemplate> customized(PromptKind kind) {
        return customized(kind, PromptScope.GLOBAL);
    }

    /** The stored override at this exact scope (no fallback), or empty when this scope has none. */
    public Optional<PromptTemplate> customized(PromptKind kind, String scope) {
        return row(kind, scope).map(r -> new PromptTemplate(kind, r.system(), r.body()));
    }

    @Transactional
    public void save(PromptKind kind, String system, String body) {
        save(kind, PromptScope.GLOBAL, system, body);
    }

    @Transactional
    public void save(PromptKind kind, String scope, String system, String body) {
        PromptTemplate base = PromptCatalog.defaultTemplate(kind);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO prompt_template
                         (scope, kind, system_text, body_text, base_system_text, base_body_text, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, now())
                     ON CONFLICT (scope, kind) DO UPDATE
                         SET system_text      = EXCLUDED.system_text,
                             body_text        = EXCLUDED.body_text,
                             base_system_text = EXCLUDED.base_system_text,
                             base_body_text   = EXCLUDED.base_body_text,
                             updated_at       = now()
                     """)) {
            ps.setString(1, scope);
            ps.setString(2, kind.slug());
            ps.setString(3, system);
            ps.setString(4, body);
            ps.setString(5, base.system());
            ps.setString(6, base.body());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save prompt template " + kind.slug()
                    + " for scope " + scope, e);
        }
    }

    /** Whether the built-in default has moved since this kind was customized. */
    public record Drift(boolean baseKnown, boolean defaultDrifted, String baseSystem, String baseBody) {
    }

    /** Drift for the global row. See {@link #drift(PromptKind, String)}. */
    public Drift drift(PromptKind kind) {
        return drift(kind, PromptScope.GLOBAL);
    }

    /**
     * Compares the stored ancestor (the default as it stood when the operator last saved this exact
     * scope) against the default shipping now. Scope-exact, no fallback: drift is a property of a
     * single customization, and after re-keying on {@code (scope, kind)} a customization is per
     * scope, so reading any other scope's row here -- even to resolve an effective template -- would
     * silently answer the wrong question.
     *
     * <p>An uncustomized scope has nothing to fork from, so it is reported as known and undrifted; a
     * row written before V33 has no recorded ancestor and drift is unknowable, not "up to date".
     */
    public Drift drift(PromptKind kind, String scope) {
        return driftOf(row(kind, scope), kind);
    }

    private Drift driftOf(Optional<Row> stored, PromptKind kind) {
        if (stored.isEmpty()) {
            return new Drift(true, false, null, null);   // not customized: nothing to drift from
        }
        Row r = stored.get();
        if (r.baseSystem() == null && r.baseBody() == null) {
            return new Drift(false, false, null, null);  // predates V33 -- unknown, not up to date
        }
        PromptTemplate current = PromptCatalog.defaultTemplate(kind);
        boolean drifted = !current.system().equals(r.baseSystem())
                || !current.body().equals(r.baseBody());
        return new Drift(true, drifted, r.baseSystem(), r.baseBody());
    }

    /** Keep the global customization, stop reporting drift. See {@link #acceptCurrentDefault(PromptKind, String)}. */
    @Transactional
    public boolean acceptCurrentDefault(PromptKind kind) {
        return acceptCurrentDefault(kind, PromptScope.GLOBAL);
    }

    /**
     * Keep the customization at this exact scope, stop reporting drift: re-stamp the ancestor to
     * what ships now. Scope-exact for the same reason {@link #drift(PromptKind, String)} is -- an
     * unscoped {@code WHERE kind = ?} would re-stamp whichever row Postgres happened to return,
     * silently touching a scope the operator was not looking at.
     *
     * @return whether a row existed at this scope to re-stamp -- false means there was nothing to
     *         accept the default for, mirroring {@link #reset(PromptKind, String)}'s own boolean.
     */
    @Transactional
    public boolean acceptCurrentDefault(PromptKind kind, String scope) {
        PromptTemplate current = PromptCatalog.defaultTemplate(kind);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE prompt_template
                        SET base_system_text = ?,
                            base_body_text   = ?,
                            updated_at       = now()
                      WHERE scope = ? AND kind = ?
                     """)) {
            ps.setString(1, current.system());
            ps.setString(2, current.body());
            ps.setString(3, scope);
            ps.setString(4, kind.slug());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to accept current default for " + kind.slug()
                    + " at scope " + scope, e);
        }
    }

    @Transactional
    public boolean reset(PromptKind kind) {
        return reset(kind, PromptScope.GLOBAL);
    }

    @Transactional
    public boolean reset(PromptKind kind, String scope) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "DELETE FROM prompt_template WHERE scope = ? AND kind = ?")) {
            ps.setString(1, scope);
            ps.setString(2, kind.slug());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to reset prompt template " + kind.slug()
                    + " at scope " + scope, e);
        }
    }

    /** A resolved row paired with which scope actually supplied it -- {@code "repo"}, {@code "global"}
     *  or {@code "default"} -- so {@link #effective(PromptKind, String)} can tell the operator where
     *  the text they're looking at came from, not just what it is. */
    private record Resolved(Optional<Row> row, String inheritedFrom) {
    }

    /** The repo row if present, else the global row, else neither. */
    private Resolved resolvedRow(PromptKind kind, String scope) {
        if (!PromptScope.GLOBAL.equals(scope)) {
            Optional<Row> repoRow = row(kind, scope);
            if (repoRow.isPresent()) {
                return new Resolved(repoRow, "repo");
            }
        }
        Optional<Row> globalRow = row(kind, PromptScope.GLOBAL);
        return globalRow.isPresent() ? new Resolved(globalRow, "global") : new Resolved(Optional.empty(), "default");
    }

    /** The row at this exact scope only -- no fallback. */
    private Optional<Row> row(PromptKind kind, String scope) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT system_text, body_text, base_system_text, base_body_text, updated_at
                       FROM prompt_template WHERE scope = ? AND kind = ?
                     """)) {
            ps.setString(1, scope);
            ps.setString(2, kind.slug());
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                Timestamp ts = rs.getTimestamp("updated_at");
                return Optional.of(new Row(rs.getString("system_text"), rs.getString("body_text"),
                        rs.getString("base_system_text"), rs.getString("base_body_text"),
                        ts == null ? null : ts.toInstant()));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load prompt template " + kind.slug()
                    + " at scope " + scope, e);
        }
    }

    private record Row(String system, String body, String baseSystem, String baseBody, Instant updatedAt) {
    }
}
