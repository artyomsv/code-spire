package dev.codespire.orchestrator.memory;

import dev.codespire.contract.review.Finding;
import dev.codespire.contract.review.ReviewResult;
import dev.codespire.contract.scm.RepoRef;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * Hides the findings an operator has approved hiding — visibly (P4 / FR-10, ADR-027).
 *
 * <p>Runs in the orchestrator between {@code ReviewGenerated} and {@code PostComments}, which is the
 * only site where the findings and the preferences are both in hand: {@code learned_preference}
 * lives in this service's schema, and {@code suppressed_by} can be written in the same pass. Doing it
 * in the worker would mean command-carrying approved preferences per review the way credentials and
 * prompts are (ADR-015) — a strictly larger change for no benefit.
 *
 * <p><b>The model still reviews exactly as it did.</b> This does not touch the prompt, and that is
 * the decision rather than an implementation detail: prompt injection might produce better findings
 * rather than merely fewer, but nothing can tell whether the model honoured the instruction, and a
 * finding it silently skipped leaves no trace anywhere. This project has twice paid for a mechanism
 * that looked installed and was not — the circuit breaker recording a failed future as a success, and
 * ADR-023's {@code 0} that meant <em>unknown</em>. A filter that reports what it removed cannot fail
 * that way.
 */
@ApplicationScoped
public class PreferenceFilter {

    private static final Logger LOG = Logger.getLogger(PreferenceFilter.class);

    /**
     * What survived, and what was hidden by which preference.
     *
     * <p>{@code suppressed} is returned rather than discarded because the rows have to be written
     * with the preference that hid them: a preference that starts hiding findings the team would have
     * acted on is detectable only if the evidence is still there to count.
     */
    public record Filtered(ReviewResult result, List<Suppression> suppressed) {

        public int suppressedCount() {
            return suppressed.size();
        }
    }

    /** One hidden finding and the preference responsible. */
    public record Suppression(Finding finding, long preferenceId) {
    }

    @Inject
    LearnedPreferences preferences;

    /**
     * Applies every approved preference for the repository.
     *
     * <p>Returns the input untouched when nothing matches, so the ordinary case allocates nothing and
     * a deployment with no preferences behaves exactly as it did before this existed.
     */
    public Filtered apply(RepoRef repo, ReviewResult result) {
        if (result == null || result.findings().isEmpty()) {
            return new Filtered(result, List.of());
        }
        List<LearnedPreferences.Preference> active =
                preferences.approvedFor(repo.workspace(), repo.slug());
        if (active.isEmpty()) {
            return new Filtered(result, List.of());
        }

        List<Finding> kept = new ArrayList<>();
        List<Suppression> suppressed = new ArrayList<>();
        for (Finding finding : result.findings()) {
            LearnedPreferences.Preference hiding = firstCovering(active, finding);
            if (hiding == null) {
                kept.add(finding);
            } else {
                suppressed.add(new Suppression(finding, hiding.id()));
            }
        }
        if (suppressed.isEmpty()) {
            return new Filtered(result, List.of());
        }
        LOG.infof("Learned preferences hid %d of %d findings", suppressed.size(),
                result.findings().size());
        return new Filtered(result.withFindings(kept), suppressed);
    }

    private static LearnedPreferences.Preference firstCovering(
            List<LearnedPreferences.Preference> active, Finding finding) {
        for (LearnedPreferences.Preference preference : active) {
            if (preference.covers(finding)) {
                return preference;
            }
        }
        return null;
    }
}
