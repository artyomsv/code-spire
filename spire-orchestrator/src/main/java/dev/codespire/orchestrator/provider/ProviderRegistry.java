package dev.codespire.orchestrator.provider;

import dev.codespire.crypto.CryptoService;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The SCM provider registry (DATA-MODEL / ADR-009) — CRUD over {@code scm_provider}
 * + {@code provider_author}, the single owner of provider credentials. Secrets are
 * Tink-encrypted at rest ({@link CryptoService}, AAD bound to the provider id) and
 * are NEVER returned by the API — only resolved internally to build an SCM client.
 */
@ApplicationScoped
public class ProviderRegistry {

    @Inject
    DataSource dataSource;

    @Inject
    CryptoService crypto;

    // ---- reads (API) -------------------------------------------------------

    public List<ProviderView> list() {
        try (Connection c = dataSource.getConnection()) {
            Map<UUID, List<String>> authors = allAuthors(c);
            List<ProviderView> out = new ArrayList<>();
            try (PreparedStatement ps = c.prepareStatement("SELECT * FROM scm_provider ORDER BY created_at");
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(toView(rs, authors.getOrDefault(rs.getObject("id", UUID.class), List.of())));
                }
            }
            return out;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to list providers", e);
        }
    }

    public Optional<ProviderView> get(UUID id) {
        try (Connection c = dataSource.getConnection()) {
            return row(c, id).map(rs -> viewOf(c, id, rs));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to load provider " + id, e);
        }
    }

    // ---- writes (API) ------------------------------------------------------

    @Transactional
    public ProviderView create(ProviderInput in) {
        UUID id = UUID.randomUUID();
        String secret = require(in.secret(), "secret");
        try (Connection c = dataSource.getConnection()) {
            try (PreparedStatement ps = c.prepareStatement("""
                    INSERT INTO scm_provider (id, name, type, base_url, workspace, auth_kind,
                            auth_username, auth_secret, bot_account_id, enabled)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                ps.setObject(1, id);
                ps.setString(2, in.name());
                ps.setString(3, in.type());
                ps.setString(4, in.baseUrl());
                ps.setString(5, in.workspace());
                ps.setString(6, in.authKind());
                ps.setString(7, blankToNull(in.authUsername()));
                ps.setString(8, crypto.encryptString(secret, aad(id)));
                ps.setString(9, in.botAccountId() == null ? "" : in.botAccountId());
                ps.setBoolean(10, in.enabled() == null || in.enabled());
                ps.executeUpdate();
            }
            replaceAuthors(c, id, in.authors());
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to create provider", e);
        }
        return get(id).orElseThrow();
    }

    @Transactional
    public Optional<ProviderView> update(UUID id, ProviderInput in) {
        try (Connection c = dataSource.getConnection()) {
            if (row(c, id).isEmpty()) {
                return Optional.empty();
            }
            boolean rotateSecret = in.secret() != null && !in.secret().isBlank();
            String sql = "UPDATE scm_provider SET name=?, type=?, base_url=?, workspace=?, auth_kind=?, "
                    + "auth_username=?, bot_account_id=?, enabled=?, updated_at=now()"
                    + (rotateSecret ? ", auth_secret=?" : "") + " WHERE id=?";
            try (PreparedStatement ps = c.prepareStatement(sql)) {
                ps.setString(1, in.name());
                ps.setString(2, in.type());
                ps.setString(3, in.baseUrl());
                ps.setString(4, in.workspace());
                ps.setString(5, in.authKind());
                ps.setString(6, blankToNull(in.authUsername()));
                ps.setString(7, in.botAccountId() == null ? "" : in.botAccountId());
                ps.setBoolean(8, in.enabled() == null || in.enabled());
                int idx = 9;
                if (rotateSecret) {
                    ps.setString(idx++, crypto.encryptString(in.secret(), aad(id)));
                }
                ps.setObject(idx, id);
                ps.executeUpdate();
            }
            replaceAuthors(c, id, in.authors());
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to update provider " + id, e);
        }
        return get(id);
    }

    @Transactional
    public boolean delete(UUID id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement("DELETE FROM scm_provider WHERE id = ?")) {
            ps.setObject(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to delete provider " + id, e);
        }
    }

    // ---- resolution (internal — carries the decrypted secret) --------------

    /** The enabled provider for a (type, workspace), with its secret decrypted. */
    public Optional<ScmProvider> resolve(String type, String workspace) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "SELECT * FROM scm_provider WHERE type = ? AND workspace = ? AND enabled = TRUE")) {
            ps.setString(1, type);
            ps.setString(2, workspace);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return Optional.empty();
                }
                UUID id = rs.getObject("id", UUID.class);
                return Optional.of(new ScmProvider(id, rs.getString("name"), rs.getString("type"),
                        rs.getString("base_url"), rs.getString("workspace"), rs.getString("auth_kind"),
                        rs.getString("auth_username"),
                        crypto.decryptString(rs.getString("auth_secret"), aad(id)),
                        rs.getString("bot_account_id"), rs.getBoolean("enabled"), authorsOf(c, id)));
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to resolve provider for " + type + "/" + workspace, e);
        }
    }

    // ---- helpers -----------------------------------------------------------

    private ProviderView viewOf(Connection c, UUID id, ResultSet rs) {
        try {
            return toView(rs, authorsOf(c, id));
        } catch (SQLException e) {
            throw new IllegalStateException("Failed to map provider " + id, e);
        }
    }

    private Optional<ResultSet> row(Connection c, UUID id) throws SQLException {
        PreparedStatement ps = c.prepareStatement("SELECT * FROM scm_provider WHERE id = ?");
        ps.setObject(1, id);
        ResultSet rs = ps.executeQuery();
        return rs.next() ? Optional.of(rs) : Optional.empty();
    }

    private ProviderView toView(ResultSet rs, List<String> authors) throws SQLException {
        String secret = rs.getString("auth_secret");
        return new ProviderView(
                rs.getObject("id", UUID.class).toString(),
                rs.getString("name"), rs.getString("type"), rs.getString("base_url"),
                rs.getString("workspace"), rs.getString("auth_kind"), rs.getString("auth_username"),
                secret != null && !secret.isBlank(),
                rs.getString("bot_account_id"), rs.getBoolean("enabled"), authors,
                rs.getTimestamp("created_at").toInstant());
    }

    private List<String> authorsOf(Connection c, UUID id) throws SQLException {
        List<String> out = new ArrayList<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT author FROM provider_author WHERE provider_id = ? ORDER BY author")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    out.add(rs.getString("author"));
                }
            }
        }
        return out;
    }

    private Map<UUID, List<String>> allAuthors(Connection c) throws SQLException {
        Map<UUID, List<String>> map = new LinkedHashMap<>();
        try (PreparedStatement ps = c.prepareStatement(
                "SELECT provider_id, author FROM provider_author ORDER BY author");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                map.computeIfAbsent(rs.getObject("provider_id", UUID.class), k -> new ArrayList<>())
                        .add(rs.getString("author"));
            }
        }
        return map;
    }

    private void replaceAuthors(Connection c, UUID id, List<String> authors) throws SQLException {
        try (PreparedStatement del = c.prepareStatement("DELETE FROM provider_author WHERE provider_id = ?")) {
            del.setObject(1, id);
            del.executeUpdate();
        }
        if (authors == null || authors.isEmpty()) {
            return;
        }
        try (PreparedStatement ins = c.prepareStatement(
                "INSERT INTO provider_author (provider_id, author) VALUES (?, ?) ON CONFLICT DO NOTHING")) {
            for (String author : authors) {
                if (author != null && !author.isBlank()) {
                    ins.setObject(1, id);
                    ins.setString(2, author.trim());
                    ins.addBatch();
                }
            }
            ins.executeBatch();
        }
    }

    private static String aad(UUID id) {
        return "provider:" + id;
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }

    private static String require(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Provider '" + field + "' is required");
        }
        return value;
    }
}
