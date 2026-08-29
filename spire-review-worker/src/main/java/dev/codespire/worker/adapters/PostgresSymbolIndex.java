package dev.codespire.worker.adapters;

import dev.codespire.contract.port.SymbolIndex;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Postgres-backed {@link SymbolIndex} — rung 2's structural index (ADR-026 §7).
 *
 * <p>Worker-owned, schema-per-service, alongside {@code comment_idempotency} and
 * {@code context_blob}. The port lives in {@code spire-contract} because ADR-021 forbids the
 * Apache-2.0 provider depending on this FSL module; the provider is handed an instance.
 *
 * <p><b>Everything here returns candidates, never answers.</b> {@link #callersOf} names files that
 * were observed to mention a symbol at some commit; the caller re-fetches them at the review commit
 * and confirms before citing. That is why no method compares {@code last_seen_commit} against
 * anything — per §7.2 those columns are diagnostic and pruning metadata only, and a read that
 * filtered on them would have reintroduced the invalidation pass the design exists to avoid.
 *
 * <p>Structure only: identifiers and paths. No hunk text, no file bodies.
 */
@ApplicationScoped
public class PostgresSymbolIndex implements SymbolIndex {

    private static final Logger LOG = Logger.getLogger(PostgresSymbolIndex.class);

    static final String DEFINES = "DEFINES";
    static final String REFERENCES = "REFERENCES";

    /**
     * Ceiling on candidates returned for one symbol. A very common identifier would otherwise return
     * hundreds of paths, and the caller must FETCH each candidate to confirm it — so an unbounded
     * read here turns into unbounded network work and an unbounded prompt. Ordered by most recently
     * seen, on the reasoning that the actively-changing part of the repository is the part a reviewer
     * is most likely to care about.
     */
    static final int MAX_CANDIDATES = 25;

    /**
     * Rows written for one file in a single statement batch. A large file can define and reference
     * hundreds of identifiers, and the index is a side effect of reviewing rather than the point of
     * it — so the write is bounded and the excess dropped rather than allowed to dominate a review.
     * Dropping costs recall, never correctness (§7.4).
     */
    static final int MAX_ROWS_PER_FILE = 400;

    @Inject
    DataSource dataSource;

    @ConfigProperty(name = "spire.symbol-index.retention-days", defaultValue = "90")
    int retentionDays;

    @Override
    public List<String> callersOf(String repo, String symbol) {
        if (isBlank(repo) || isBlank(symbol)) {
            return List.of();
        }
        String sql = """
                SELECT path FROM code_symbol
                 WHERE repo = ? AND symbol = ? AND role = ?
                 ORDER BY last_seen_at DESC
                 LIMIT ?
                """;
        List<String> paths = new ArrayList<>();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, repo);
            ps.setString(2, symbol);
            ps.setString(3, REFERENCES);
            ps.setInt(4, MAX_CANDIDATES);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    paths.add(rs.getString("path"));
                }
            }
        } catch (SQLException e) {
            // Never fail a review because the index is unavailable. It only narrows a search, so an
            // empty answer costs recall — the caller simply cites nothing about callers, which is
            // exactly what rung 1 did.
            LOG.warnf(e, "Symbol index unavailable for %s/%s — continuing without caller candidates",
                    repo, symbol);
            return List.of();
        }
        return paths;
    }

    @Override
    public void record(String repo, String path, String commit,
                       List<String> defines, List<String> references) {
        if (isBlank(repo) || isBlank(path) || isBlank(commit)) {
            return;
        }
        String sql = """
                INSERT INTO code_symbol (repo, symbol, path, role, last_seen_commit, last_seen_at)
                VALUES (?, ?, ?, ?, ?, now())
                ON CONFLICT (repo, symbol, path, role)
                DO UPDATE SET last_seen_commit = EXCLUDED.last_seen_commit,
                              last_seen_at     = EXCLUDED.last_seen_at
                """;
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            int written = 0;
            written += batch(ps, repo, path, commit, defines, DEFINES, written);
            written += batch(ps, repo, path, commit, references, REFERENCES, written);
            if (written > 0) {
                ps.executeBatch();
            }
        } catch (SQLException e) {
            // Same reasoning as the read: recording is a side effect of reviewing, and a review must
            // not fail because the index could not be updated.
            LOG.warnf(e, "Could not record symbols for %s %s — the index will be missing this file",
                    repo, path);
        }
    }

    /** Prune rows not confirmed within the retention window. Costs recall, never correctness. */
    public int prune() {
        String sql = "DELETE FROM code_symbol WHERE last_seen_at < now() - make_interval(days => ?)";
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, retentionDays);
            int removed = ps.executeUpdate();
            if (removed > 0) {
                LOG.infof("Pruned %d symbol row(s) not seen in %d days", removed, retentionDays);
            }
            return removed;
        } catch (SQLException e) {
            LOG.warnf(e, "Symbol index prune failed — the index keeps growing until the next attempt");
            return 0;
        }
    }

    private static int batch(PreparedStatement ps, String repo, String path, String commit,
                             List<String> symbols, String role, int alreadyWritten) throws SQLException {
        if (symbols == null) {
            return 0;
        }
        int added = 0;
        for (String symbol : symbols) {
            if (alreadyWritten + added >= MAX_ROWS_PER_FILE) {
                break;
            }
            if (isBlank(symbol)) {
                continue;
            }
            ps.setString(1, repo);
            ps.setString(2, symbol);
            ps.setString(3, path);
            ps.setString(4, role);
            ps.setString(5, commit);
            ps.addBatch();
            added++;
        }
        return added;
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
