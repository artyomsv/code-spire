package dev.codespire.orchestrator.factory;

import dev.codespire.contract.event.RunIds;
import dev.codespire.contract.port.ScmType;
import dev.codespire.contract.scm.RepoRef;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import java.util.Optional;

/**
 * Whether a {@code /fix} becomes a run, and what that run is told (FR-F27, ADR-040).
 *
 * <p>Its own class rather than more of {@link dev.codespire.orchestrator.pipeline.IntegrationSaga},
 * which is the shape {@code ConversationFindings} already set for {@code /finding}: the saga
 * dispatches on a result, and the rules are unit-testable without a saga fixture. That saga is also
 * already past this project's size guideline, so growing it is a choice worth not making.
 *
 * <p><b>Every refusal carries a reason.</b> The author typed a command; a silent "nothing happened"
 * is the symptom this project has paid for twice, and the caller cannot reconstruct the reason from a
 * bare empty answer without re-doing every check.
 */
@ApplicationScoped
public class FixDispatch {

    /**
     * How many fix runs one finding may have before a human should look at why they are not landing.
     *
     * <p>Constants rather than settings for now, and that is a gap the dispatch slice inherits rather
     * than one it introduces: FR-F32 bounds a runaway LOOP, which is not the same as ADR-025's spend
     * cap where unset is an operator's deliberate opt-in. Reading them from configuration wants a
     * startup refusal when unset, which is its own change.
     */
    static final int MAX_PER_FINDING = 2;

    /** And how many one review may have — the axis that bounds the fix-review-fix chain. */
    static final int MAX_PER_REVIEW = 5;

    @Inject
    FixTargets targets;

    @Inject
    FixRuns fixRuns;

    /** A fix run that may be dispatched, and everything ADR-040 needs it to be told. */
    public sealed interface Plan permits Planned, Refused {
    }

    /**
     * @param baseBranch what the publisher CLONES, and {@code branch} what it PUSHES to — the same
     *     branch here, which is exactly what ADR-040's {@code existing} mode exists to permit and
     *     what the default mode refuses
     * @param protectedBranch the pull request's destination, which the publisher refuses in every
     *     mode
     */
    public record Planned(String runId, String baseBranch, String branch, String baseCommit,
                          String protectedBranch, String providerType, String workspace,
                          String slug) implements Plan {
    }

    /** Refused, in words the author can act on. */
    public record Refused(String why) implements Plan {
    }

    /**
     * Plan a fix run for the finding a thread names.
     *
     * <p><b>The cap is consulted BEFORE the target is proven pushable, and that order is deliberate.</b>
     * A capped finding on a merged pull request should be told it is capped: the cap is a durable fact
     * an operator set, while "merged" is a state that changed. Reporting the transient reason would
     * send someone to reopen a pull request the cap would refuse anyway.
     */
    public Plan plan(String reviewId, String threadRef, RepoRef repo) {
        FixRuns.Decision capped = fixRuns.decide(reviewId, threadRef, MAX_PER_FINDING, MAX_PER_REVIEW);
        if (!capped.allowed()) {
            return new Refused(capped.why());
        }
        Optional<FixTargets.PushTarget> found = targets.forReview(reviewId);
        if (found.isEmpty()) {
            return new Refused("no pull request is recorded for this review, so there is nowhere to "
                    + "push a fix");
        }
        FixTargets.PushTarget target = found.get();
        // ADR-040 §3 asks for this in as many words, and without it the guard existed, was tested,
        // and was called by nothing. The shape was inside-out: plan resolved coordinates from the
        // review and REPORTED them, instead of being told the ones the comment arrived on and
        // PROVING they match. The hazard is one step less exotic than the fork gap — a branch name
        // resolved against one repository and pushed against another.
        if (!target.belongsTo(repo)) {
            return new Refused("this review is recorded against a different repository than the "
                    + "comment came from, so a fix would be pushed somewhere else entirely");
        }
        Optional<FixTargets.Unpushable> unpushable = target.whyNotPushable();
        if (unpushable.isPresent()) {
            return new Refused(wording(unpushable.get()));
        }
        // The finding's thread is the subject, so a second fix for the same finding derives a
        // different run id through the attempt rather than colliding with the first and being
        // dropped by the worker's claim as a redelivery.
        Optional<ScmType> scmType = ScmType.fromProviderType(target.providerType());
        if (scmType.isEmpty()) {
            // The row stores whatever provider type was registered; an unrecognised one means the
            // registration and this build disagree, which is an operator-visible fault rather than
            // something to guess past on the way to spending money.
            return new Refused("this review was recorded under an SCM this build does not recognise ("
                    + target.providerType() + ")");
        }
        String runId;
        try {
            runId = RunIds.of(scmType.get(), target.workspace(), target.slug(),
                    threadRef, fixRuns.nextAttempt(reviewId, threadRef));
        } catch (IllegalArgumentException cannotAddress) {
            // RunIds refuses a blank or ':'-bearing component, and threadRef is forge-supplied with
            // no upstream guard on its characters. An escaping exception is NOT a Refused: it
            // dead-letters through a channel that acks on receipt, so the author who typed /fix
            // gets exactly the silence this class exists to avoid.
            return new Refused("this review's recorded coordinates cannot address a run ("
                    + cannotAddress.getMessage() + ") — an operator should look at the review row");
        }
        return new Planned(runId, target.sourceBranch(), target.sourceBranch(), target.commit(),
                target.destBranch(), target.providerType(), target.workspace(), target.slug());
    }

    /**
     * What to tell the author, for a cause the read model decided.
     *
     * <p><b>This class owns the wording and no longer owns the rule.</b> It used to re-derive both,
     * which is two encodings of one thing — and the test asserting they agreed could only check
     * WHETHER, never WHICH, so swapping two causes passed it. The switch is exhaustive over the
     * enum, so a cause added to the read model without wording here fails the build.
     */
    private static String wording(FixTargets.Unpushable cause) {
        return switch (cause) {
            case FORK -> "that pull request comes from a fork, and a fix pushes to the branch it was "
                    + "opened from — which lives in the contributor's repository, not this one";
            case NOT_OPEN -> "that pull request is no longer open, so a fix would land on a branch "
                    + "nobody is reviewing and no later round would reconcile it";
            case NOT_RECORDED_YET -> "this review has no recorded branch or head commit yet — push to "
                    + "the pull request once and try again";
        };
    }
}
