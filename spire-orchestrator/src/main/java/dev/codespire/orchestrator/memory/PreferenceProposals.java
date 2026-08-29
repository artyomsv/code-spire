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

    /**
     * Kinds of finding no preference may ever hide, whatever the evidence says.
     *
     * <p>The admin approval was treated as the only gate, and it is weaker than it looks. An
     * {@code ACKNOWLEDGED} verdict comes from the reconcile model reading the pull request author's
     * OWN thread replies — so an author can manufacture the evidence by replying "won't fix" to ten
     * findings of one kind. The card cannot show an admin that, either: ten dismissals by one person
     * in one pull request look exactly like ten across ten teams.
     *
     * <p>For a naming nit that is a tolerable trade. For a security finding it is a way to teach the
     * reviewer to stop mentioning security in a directory. So this is a floor, not a default: it is
     * enforced at BOTH ends — no proposal is generated here, and {@code PreferenceFilter} refuses to
     * act on one even if a row somehow reaches {@code APPROVED}.
     */
    static final java.util.Set<String> NEVER_SUPPRESSED_CATEGORIES = java.util.Set.of("SECURITY");

    /** Same reasoning, on the other axis: a blocker is by definition not a preference. */
    static final java.util.Set<String> NEVER_SUPPRESSED_SEVERITIES = java.util.Set.of("BLOCKER");

    /** Pull requests a group must span. One author on one PR is not a team preference. */
    static final int MIN_DISTINCT_REVIEWS = 2;

    /**
     * How far back the scan looks.
     *
     * <p>Two reasons, and the second is the one that matters. It bounds an aggregate an admin can
     * trigger on demand over a table that only grows — the {@code GROUP BY} has no index that fits
     * it, so an unbounded scan gets slower every week. And a preference should reflect what a team
     * believes NOW: dismissals from two years ago are evidence about people who may have left.
     */
    @ConfigProperty(name = "spire.memory.window-days", defaultValue = "180")
    int windowDays;

    /** Judged findings a group needs before it can say anything. */
    @ConfigProperty(name = "spire.memory.min-evidence", defaultValue = "10")
    int minEvidence;

    /** Share of them that must have been dismissed, as a percentage. */
    @ConfigProperty(name = "spire.memory.min-dismissed-percent", defaultValue = "75")
    int minDismissedPercent;

    /**
     * The thresholds, as METHODS.
     *
     * <p>Not a field read from outside this class, and the difference is invisible until it ships:
     * this bean is {@code @ApplicationScoped}, so every injected reference is a client proxy, and a
     * proxy delegates method calls but NOT field access. Reading {@code proposals.minEvidence}
     * directly returns the proxy instance's own uninitialised {@code 0} -- so the Memory screen
     * would have shown 'threshold: 0 findings, 0% dismissed' while the job enforced 10 and 75.
     * An operator judging a proposal against a bar of zero is judging against no bar at all.
     */
    public int minEvidence() {
        return minEvidence;
    }

    public int minDismissedPercent() {
        return minDismissedPercent;
    }

    /**
     * Judged, categorised, not-already-hidden findings inside the window, grouped per path.
     *
     * <p>An uncategorised finding cannot be grouped and is excluded here rather than bucketed into
     * OTHER, which would put "the model did not say" and "the model said OTHER" in one pile.
     */
    private static final String CANDIDATE_GROUPS = """
                SELECT s.workspace, s.slug, s.provider_type, f.category, f.severity, f.path,
                       count(*)                                        AS judged,
                       count(*) FILTER (WHERE f.verdict IN %s)         AS dismissed,
                       count(DISTINCT f.review_id)                     AS reviews
                  FROM review_finding f JOIN review_status s ON s.review_id = f.review_id
                 WHERE f.verdict IS NOT NULL
                   AND f.category IS NOT NULL
                   AND f.suppressed_by IS NULL
                   AND f.created_at >= now() - make_interval(days => ?)
                   AND f.category <> ALL (?)
                   AND f.severity <> ALL (?)
                 GROUP BY s.workspace, s.slug, s.provider_type, f.category, f.severity, f.path
                """;;

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
        // Grouping happens in SQL over the clear columns; nothing decrypts. See CANDIDATE_GROUPS.
        String sql = CANDIDATE_GROUPS.formatted(DISMISSED);

        Groups groups = new Groups();
        try (Connection c = dataSource.getConnection();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, windowDays);
            ps.setArray(2, c.createArrayOf("varchar", NEVER_SUPPRESSED_CATEGORIES.toArray()));
            ps.setArray(3, c.createArrayOf("varchar", NEVER_SUPPRESSED_SEVERITIES.toArray()));
            try (ResultSet rs = ps.executeQuery()) {
                accumulate(rs, groups);
            }
        } catch (SQLException e) {
            LOG.warnf(e, "Could not scan for learned preferences — nothing proposed this run");
            return 0;
        }
        return groups.proposeQualifying(this);
    }

    /**
     * Big enough, consistent enough, and drawn from more than one pull request.
     *
     * <p>The distinct-review floor is the cheap half of the answer to manufactured evidence: ten
     * "won't fix" replies by one author on one pull request no longer qualify, whatever the
     * percentage says. It does not make the signal trustworthy on its own, which is why the count
     * also reaches the card an admin decides from.
     */
    /**
     * Folds the per-path rows into per-glob groups.
     *
     * <p>Paths collapse to a glob in Java rather than in SQL, because the ladder IS the group's
     * identity and it has to be the same ladder the filter matches with. Two implementations would
     * be free to drift, and a drifted glob silently re-proposes a group an operator already
     * rejected — the one guarantee {@code REJECTED} exists to give.
     *
     * <p>The scope carries the PLATFORM. One workspace name registered on two SCMs is the collision
     * this project has been bitten by twice; pooling the evidence would let one team's dismissals
     * hide findings from another team that dismissed nothing.
     */
    private static void accumulate(ResultSet rs, Groups groups) throws SQLException {
        while (rs.next()) {
            String glob = PathGlobs.of(rs.getString("path"));
            if (glob == null) {
                continue;
            }
            groups.add(new Key(rs.getString("provider_type") + ":" + rs.getString("workspace")
                    + "/" + rs.getString("slug"),
                    rs.getString("category"), glob, rs.getString("severity")),
                    rs.getInt("judged"), rs.getInt("dismissed"), rs.getInt("reviews"));
        }
    }

    boolean qualifies(int judged, int dismissed, int reviews) {
        return judged >= minEvidence
                && reviews >= MIN_DISTINCT_REVIEWS
                && dismissed * 100 >= judged * minDismissedPercent;
    }

    void propose(Key key, int judged, int dismissed, int reviews) {
        preferences.propose(new LearnedPreferences.Preference(0, LearnedPreferences.SCOPE_REPO,
                key.repo(), key.category(), key.pathGlob(), key.severity(),
                LearnedPreferences.PROPOSED, judged, dismissed, reviews));
    }

    /** One candidate group. */
    record Key(String repo, String category, String pathGlob, String severity) {
    }

    /**
     * Accumulates per-path rows into per-glob groups, then proposes the ones that qualify.
     *
     * <p>The review count is a MAX rather than a sum: several paths under one glob are usually the
     * same pull requests seen again, so adding them would multiply one review into many and defeat
     * the distinct-review floor. Max under-counts instead, which is the safe direction for a floor.
     */
    private static final class Groups {
        private final java.util.Map<Key, int[]> totals = new java.util.LinkedHashMap<>();

        void add(Key key, int judged, int dismissed, int reviews) {
            int[] counts = totals.computeIfAbsent(key, k -> new int[3]);
            counts[0] += judged;
            counts[1] += dismissed;
            counts[2] = Math.max(counts[2], reviews);
        }

        int proposeQualifying(PreferenceProposals owner) {
            int proposed = 0;
            for (var entry : totals.entrySet()) {
                int judged = entry.getValue()[0];
                int dismissed = entry.getValue()[1];
                int reviews = entry.getValue()[2];
                if (owner.qualifies(judged, dismissed, reviews)) {
                    owner.propose(entry.getKey(), judged, dismissed, reviews);
                    proposed++;
                }
            }
            return proposed;
        }
    }
}
