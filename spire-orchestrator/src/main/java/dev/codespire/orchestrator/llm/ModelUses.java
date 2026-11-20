package dev.codespire.orchestrator.llm;

import dev.codespire.llmcatalog.CatalogModel;
import dev.codespire.orchestrator.llm.catalog.CatalogSnapshot;

import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Where a manual model's name is chosen: the default review model and each repository's review model. A model name is
 * a bare string in both places, not a foreign key, so deleting, renaming or retyping the model would leave the choice
 * pointing at nothing (or at another vendor) with no error until a review is refused.
 *
 * <p>The default review model counts only while a default provider exists ({@code is_default AND enabled}): without
 * one nothing spends it, and the setting is only a leftover.
 */
final class ModelUses {

    static final String DEFAULT_USE = "the default review model";

    private ModelUses() {
    }

    /** The name and type a manual model is stored with. */
    record ManualRow(String name, String type) {
    }

    /** The manual model with this id, or null when there is no such row. */
    static ManualRow manualRow(Connection c, UUID id) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT name, type FROM llm_model WHERE id = ?")) {
            ps.setObject(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? new ManualRow(rs.getString("name"), rs.getString("type")) : null;
            }
        }
    }

    /** The uses of a manual model, in a stable order, named for a message; empty when nothing chooses it. */
    static List<String> of(Connection c, CatalogSnapshot snapshot, String name) throws SQLException {
        Set<String> names = namesAnsweredTo(snapshot, name);
        List<String> uses = new ArrayList<>();
        if (isTheDefaultModel(c, names)) {
            uses.add(DEFAULT_USE);
        }
        for (String repository : repositoriesChoosing(c, names)) {
            uses.add("repository " + repository);
        }
        return uses;
    }

    /**
     * Whether a manual model other than {@code except} answers to one of these names (from {@link #namesAnsweredTo}).
     * The resolver asks the manual table for all of them, so after deleting one override such a row, not the
     * catalog entry, is what the name resolves to.
     */
    static boolean anotherManualAnswers(Connection c, Set<String> names, UUID except) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT 1 FROM llm_model WHERE id <> ? AND name = ANY(?)")) {
            ps.setObject(1, except);
            ps.setArray(2, textArray(c, names));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    /**
     * Every name a choice may hold that reaches this manual model: its own, and when it shadows a catalog entry, that
     * entry's id and aliases (the resolver asks the manual table for all of them).
     */
    static Set<String> namesAnsweredTo(CatalogSnapshot snapshot, String name) {
        Set<String> names = new LinkedHashSet<>();
        names.add(name);
        snapshot.byName(name).map(CatalogModel::names).ifPresent(names::addAll);
        return names;
    }

    private static boolean isTheDefaultModel(Connection c, Set<String> names) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("""
                SELECT 1 FROM app_setting
                 WHERE key = ? AND value = ANY(?)
                   AND EXISTS (SELECT 1 FROM llm_provider WHERE is_default = TRUE AND enabled = TRUE)
                """)) {
            ps.setString(1, LlmDefaultModel.KEY);
            ps.setArray(2, textArray(c, names));
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static List<String> repositoriesChoosing(Connection c, Set<String> names) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement("SELECT workspace || '/' || slug FROM repository"
                + " WHERE review_llm_model = ANY(?) ORDER BY workspace, slug")) {
            ps.setArray(1, textArray(c, names));
            try (ResultSet rs = ps.executeQuery()) {
                List<String> repositories = new ArrayList<>();
                while (rs.next()) {
                    repositories.add(rs.getString(1));
                }
                return repositories;
            }
        }
    }

    private static Array textArray(Connection c, Set<String> names) throws SQLException {
        return c.createArrayOf("text", names.toArray(new String[0]));
    }
}
