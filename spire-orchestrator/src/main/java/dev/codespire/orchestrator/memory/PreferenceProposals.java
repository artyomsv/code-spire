package dev.codespire.orchestrator.memory;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Notices what a team keeps dismissing, and proposes it (P4 / FR-10).
 *
 * <p>Runs nightly, groups judged findings by {@code (repository, category, path glob, severity)}, and
 * proposes when a group is both <b>big enough</b> and <b>consistent enough</b>. Both thresholds are
 * operator-editable and both are rendered on the card the operator decides from.
 *
 * <p><b>Why the thresholds are visible.</b> A proposal from eleven data points is the ADR-026 rung-2
 * gate's failure recurring — a conclusion drawn from a corpus too thin to speak, which nobody could
 * see was thin because the numbers were not on screen. Learned memory would repeat it exactly:
 * plausible-looking preferences, quietly wrong, discovered only when a real defect went unreported.
 */
@ApplicationScoped
public class PreferenceProposals {

    private static final Logger LOG = Logger.getLogger(PreferenceProposals.class);

    /**
     * The verdicts that mean a human declined the finding.
     *
     * <p>{@code STILL_OPEN} is not one of them — the finding survived, nobody rejected it — and
     * neither is {@code SUPERSEDED}, where circumstance replaced it rather than judgement.
     */
    private static final String DISMISSED = "('ACKNOWLEDGED', 'UNCHANGED')";

    /** Judged findings a group needs before it can say anything. */
    @ConfigProperty(name = "spire.memory.min-evidence", defaultValue = "10")
    int minEvidence;

    /** Share of them that must have been dismissed, as a percentage. */
    @ConfigProperty(name = "spire.memory.min-dismissed-percent", defaultValue = "75")
    int minDismissedPercent;

    @Inject
    DataSource dataSource;

    @Inject
    LearnedPreferences preferences;

    /**
     * Nightly, and skipping a concurrent run rather than queueing one.
     *
     * <p>{@code SKIP} because two passes would race on the same upsert for no benefit: the second
     * would compute identical groups from the same rows.
     */
    @Scheduled(every = "24h", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void propose() {
        int proposed = scan();
        if (proposed > 0) {
            LOG.infof("Proposed %d learned preference(s) for review", proposed);
        }
    }

    /** @return how many groups crossed both thresholds. Package-private so a test can drive it. */
    int scan() {
        // Grouping happens in SQL over the clear columns; nothing decrypts. A finding with no
        // category cannot be grouped and is excluded here rather than bucketed into OTHER, which
        // would put "the model did not say" and "the model said OTHER" in one pile.
        String sql = """
                SELECT s.workspace, s.slug, f.category, f.severity, f.path,
                       count(*)                                        AS judged,
                       count(*) FILTER (WHERE f.verdict IN %s)         AS dismissed
                  FROM review_finding f JOIN review_status s ON s.review_id = f.review_id
                 WHERE f.verdict IS NOT NULL
                   AND f.category IS NOT NULL
                   AND f.suppressed_by IS NULL
                 GROUP BY s.workspace, s.slug, f.category, f.severity, f.path
                """.formatted(DISMISSED);

        Groups groups = new Groups();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                // Paths collapse to a glob in Java rather than in SQL: the ladder is the group's
                // identity, and it has to be the same ladder the filter matches with. Two
                // implementations of it would be free to drift, and a drifted glob silently
                // re-proposes a group an operator already rejected.
                String glob = PathGlobs.of(rs.getString("path"));
                if (glob == null) {
                    continue;
                }
                groups.add(new Key(rs.getString("workspace") + "/" + rs.getString("slug"),
                        rs.getString("category"), glob, rs.getString("severity")),
                        rs.getInt("judged"), rs.getInt("dismissed"));
            }
        } catch (SQLException e) {
            LOG.warnf(e, "Could not scan for learned preferences — nothing proposed this run");
            return 0;
        }
        return groups.proposeQualifying(this);
    }

    boolean qualifies(int judged, int dismissed) {
        return judged >= minEvidence && dismissed * 100 >= judged * minDismissedPercent;
    }

    void propose(Key key, int judged, int dismissed) {
        preferences.propose(new LearnedPreferences.Preference(0, LearnedPreferences.SCOPE_REPO,
                key.repo(), key.category(), key.pathGlob(), key.severity(),
                LearnedPreferences.PROPOSED, judged, dismissed));
    }

    /** One candidate group. */
    record Key(String repo, String category, String pathGlob, String severity) {
    }

    /** Accumulates per-path rows into per-glob groups, then proposes the ones that qualify. */
    private static final class Groups {
        private final java.util.Map<Key, int[]> totals = new java.util.LinkedHashMap<>();

        void add(Key key, int judged, int dismissed) {
            int[] counts = totals.computeIfAbsent(key, k -> new int[2]);
            counts[0] += judged;
            counts[1] += dismissed;
        }

        int proposeQualifying(PreferenceProposals owner) {
            int proposed = 0;
            for (var entry : totals.entrySet()) {
                int judged = entry.getValue()[0];
                int dismissed = entry.getValue()[1];
                if (owner.qualifies(judged, dismissed)) {
                    owner.propose(entry.getKey(), judged, dismissed);
                    proposed++;
                }
            }
            return proposed;
        }
    }
}
