package dev.codespire.orchestrator.memory;

import dev.codespire.contract.review.Finding;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * The preferences a team has been observed to hold, and an operator has agreed to (P4 / FR-10).
 *
 * <p>Only an {@code APPROVED} row ever changes a review. A {@code PROPOSED} row is a question on a
 * screen; a {@code REJECTED} row is a question already answered, remembered so the nightly job does
 * not ask it again.
 */
@ApplicationScoped
public class LearnedPreferences {

    private static final Logger LOG = Logger.getLogger(LearnedPreferences.class);

    public static final String SCOPE_GLOBAL = "global";
    public static final String SCOPE_REPO = "repo";

    public static final String PROPOSED = "PROPOSED";
    public static final String APPROVED = "APPROVED";
    public static final String REJECTED = "REJECTED";

    /**
     * One preference, proposed or decided.
     *
     * <p>{@code evidenceTotal}/{@code evidenceDismissed} are carried so the card can show what the
     * proposal was made on. A proposal whose evidence is invisible is the rung-2 gate's failure
     * recurring: a conclusion from a corpus too thin to speak, which nobody could see was thin.
     */
    public record Preference(long id, String scopeType, String scopeValue, String category,
                             String pathGlob, String severity, String state,
                             int evidenceTotal, int evidenceDismissed, int evidenceReviews) {

        /** Whether this preference speaks for the repository under review. */
        public boolean appliesTo(String workspace, String slug) {
            return SCOPE_GLOBAL.equals(scopeType) || scopeValue.equals(workspace + "/" + slug);
        }

        /**
         * Whether it covers this finding. A finding with no category can never match.
         *
         * <p>The never-suppressed floor is re-checked HERE as well as in the proposal engine, and
         * the duplication is deliberate. The engine stops such a group being proposed; this stops
         * an already-{@code APPROVED} row from acting — which is what a row created before the
         * floor existed, or written directly in SQL, would otherwise do. A control that exists only
         * at the point of creation protects nothing that predates it.
         */
        public boolean covers(Finding finding) {
            if (finding.category() == null || finding.severity() == null) {
                return false;
            }
            if (PreferenceProposals.NEVER_SUPPRESSED_CATEGORIES.contains(finding.category().name())
                    || PreferenceProposals.NEVER_SUPPRESSED_SEVERITIES.contains(
                            finding.severity().name())) {
                return false;
            }
            return finding.category().name().equals(category)
                    && finding.severity().name().equals(severity)
                    && PathGlobs.matches(pathGlob, finding.path());
        }
    }

    @Inject
    DataSource dataSource;

    /** Everything, newest proposals first — the Memory screen's listing. */
    public List<Preference> all() {
        return read("SELECT * FROM learned_preference ORDER BY state, proposed_at DESC", ps -> { });
    }

    /**
     * The approved preferences that speak for one repository.
     *
     * <p>Read on every review, so a failure must be silent and permissive: an outage here means
     * findings are <em>shown</em> that an operator asked to hide, which is noisy. Failing the other
     * way would hide findings nobody approved hiding.
     */
    public List<Preference> approvedFor(String workspace, String slug) {
        try {
            return read("SELECT * FROM learned_preference WHERE state = ?", ps -> ps.setString(1, APPROVED))
                    .stream().filter(p -> p.appliesTo(workspace, slug)).toList();
        } catch (RuntimeException e) {
            LOG.warnf(e, "Could not read learned preferences for %s/%s — nothing will be hidden this run",
                    workspace, slug);
            return List.of();
        }
    }

    /**
     * Records a proposal, or refreshes the evidence on one already standing.
     *
     * <p>A decided row is left completely alone: re-proposing a rejected group every night is the
     * behaviour {@code REJECTED} exists to prevent, and refreshing an approved one would reopen a
     * settled question.
     */
    public void propose(Preference proposal) {
        String sql = """
                INSERT INTO learned_preference (scope_type, scope_value, category, path_glob, severity,
                                                state, evidence_total, evidence_dismissed,
                                                evidence_reviews)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (scope_type, scope_value, category, path_glob, severity)
                DO UPDATE SET evidence_total = EXCLUDED.evidence_total,
                              evidence_dismissed = EXCLUDED.evidence_dismissed,
                              evidence_reviews = EXCLUDED.evidence_reviews,
                              proposed_at = now()
                 WHERE learned_preference.state = ?
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, proposal.scopeType());
            ps.setString(2, proposal.scopeValue());
            ps.setString(3, proposal.category());
            ps.setString(4, proposal.pathGlob());
            ps.setString(5, proposal.severity());
            ps.setString(6, PROPOSED);
            ps.setInt(7, proposal.evidenceTotal());
            ps.setInt(8, proposal.evidenceDismissed());
            ps.setInt(9, proposal.evidenceReviews());
            ps.setString(10, PROPOSED);
            ps.executeUpdate();
        } catch (SQLException e) {
            LOG.warnf(e, "Could not record a learned-preference proposal");
        }
    }

    public boolean decide(long id, String state, String decidedBy) {
        if (!APPROVED.equals(state) && !REJECTED.equals(state)) {
            throw new IllegalArgumentException("A preference is approved or rejected, not " + state);
        }
        String sql = """
                UPDATE learned_preference SET state = ?, decided_at = now(), decided_by = ?
                 WHERE id = ?
                """;
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setString(1, state);
            ps.setString(2, decidedBy);
            ps.setLong(3, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("could not decide the preference", e);
        }
    }

    /**
     * Revokes an approved preference by returning it to {@code PROPOSED}.
     *
     * <p>Not a delete: {@code review_finding.suppressed_by} points here, and the evidence that a
     * preference was hiding the wrong things is the reason to keep the row. The next review stops
     * filtering on it immediately, with no rebuild.
     */
    public boolean revoke(long id) {
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(
                     "UPDATE learned_preference SET state = ?, decided_at = NULL, decided_by = NULL"
                             + " WHERE id = ? AND state = ?")) {
            ps.setString(1, PROPOSED);
            ps.setLong(2, id);
            ps.setString(3, APPROVED);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            throw new IllegalStateException("could not revoke the preference", e);
        }
    }

    private List<Preference> read(String sql, Binder binder) {
        List<Preference> rows = new ArrayList<>();
        try (Connection c = dataSource.getConnection(); PreparedStatement ps = c.prepareStatement(sql)) {
            binder.bind(ps);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new Preference(rs.getLong("id"), rs.getString("scope_type"),
                            rs.getString("scope_value"), rs.getString("category"),
                            rs.getString("path_glob"), rs.getString("severity"), rs.getString("state"),
                            rs.getInt("evidence_total"), rs.getInt("evidence_dismissed"),
                            rs.getInt("evidence_reviews")));
                }
            }
        } catch (SQLException e) {
            throw new IllegalStateException("could not read learned preferences", e);
        }
        return rows;
    }

    private interface Binder {
        void bind(PreparedStatement ps) throws SQLException;
    }
}
