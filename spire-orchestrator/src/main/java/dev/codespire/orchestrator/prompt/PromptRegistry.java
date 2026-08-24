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
 * CRUD over {@code prompt_template} (global, one row per kind) plus effective-template resolution:
 * a stored row overrides the built-in {@link PromptCatalog} default. No secrets, so no encryption
 * (mirrors {@code LlmModelRegistry}).
 */
@ApplicationScoped
public class PromptRegistry {

    @Inject
    DataSource dataSource;

    public List<PromptView> list() {
        List<PromptView> out = new ArrayList<>();
        for (PromptKind kind : PromptKind.values()) {
            out.add(effective(kind));
        }
        return out;
    }

    /** The effective view for a kind: the stored override if present, else the built-in default. */
    public PromptView effective(PromptKind kind) {
        Optional<Row> row = row(kind);
        PromptTemplate def = PromptCatalog.defaultTemplate(kind);
        String system = row.map(Row::system).orElse(def.system());
        String body = row.map(Row::body).orElse(def.body());
        Instant updatedAt = row.map(Row::updatedAt).orElse(null);
        Drift drift = drift(kind);
        return new PromptView(kind.slug(), row.isPresent(), system, body, updatedAt,
                PromptCatalog.palette(kind), PromptCatalog.lockedSystemSuffix(kind),
                drift.baseKnown(), drift.defaultDrifted(), def.system(), def.body(),
                drift.baseSystem(), drift.baseBody());
    }

    /** The stored override as a {@link PromptTemplate}, or empty when the kind uses the default. */
    public Optional<PromptTemplate> customized(PromptKind kind) {
        return row(kind).map(r -> new PromptTemplate(kind, r.system(), r.body()));
    }

    @Transactional
    public void save(PromptKind kind, String system, String body) {
        PromptTemplate base = PromptCatalog.defaultTemplate(kind);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     INSERT INTO prompt_template
                         (kind, system_text, body_text, base_system_text, base_body_text, updated_at)
                     VALUES (?, ?, ?, ?, ?, now())
                     ON CONFLICT (kind) DO UPDATE
                         SET system_text      = EXCLUDED.system_text,
                             body_text        = EXCLUDED.body_text,
                             base_system_text = EXCLUDED.base_system_text,
                             base_body_text   = EXCLUDED.base_body_text,
                             updated_at       = now()
                     """)) {
            ps.setString(1, kind.slug());
            ps.setString(2, system);
            ps.setString(3, body);
            ps.setString(4, base.system());
            ps.setString(5, base.body());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to save prompt template " + kind.slug(), e);
        }
    }

    /** Whether the built-in default has moved since this kind was customized. */
    public record Drift(boolean baseKnown, boolean defaultDrifted, String baseSystem, String baseBody) {
    }

    /**
     * Compares the stored ancestor (the default as it stood when the operator last saved) against
     * the default shipping now. An uncustomized kind has nothing to fork from, so it is reported as
     * known and undrifted; a row written before V33 has no recorded ancestor and drift is unknowable,
     * not "up to date".
     */
    public Drift drift(PromptKind kind) {
        Optional<Row> stored = row(kind);
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

    /** Keep the customization, stop reporting drift: re-stamp the ancestor to what ships now. */
    @Transactional
    public void acceptCurrentDefault(PromptKind kind) {
        PromptTemplate current = PromptCatalog.defaultTemplate(kind);
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     UPDATE prompt_template
                        SET base_system_text = ?,
                            base_body_text   = ?,
                            updated_at       = now()
                      WHERE kind = ?
                     """)) {
            ps.setString(1, current.system());
            ps.setString(2, current.body());
            ps.setString(3, kind.slug());
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to accept current default for " + kind.slug(), e);
        }
    }

    @Transactional
    public boolean reset(PromptKind kind) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM prompt_template WHERE kind = ?")) {
            ps.setString(1, kind.slug());
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to reset prompt template " + kind.slug(), e);
        }
    }

    private Optional<Row> row(PromptKind kind) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("""
                     SELECT system_text, body_text, base_system_text, base_body_text, updated_at
                       FROM prompt_template WHERE kind = ?
                     """)) {
            ps.setString(1, kind.slug());
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
            throw new IllegalStateException("Failed to load prompt template " + kind.slug(), e);
        }
    }

    private record Row(String system, String body, String baseSystem, String baseBody, Instant updatedAt) {
    }
}
