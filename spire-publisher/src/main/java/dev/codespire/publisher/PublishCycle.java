package dev.codespire.publisher;

import dev.codespire.workspace.AmbiguousBundleException;
import dev.codespire.workspace.BundleTooLargeException;
import dev.codespire.workspace.ChangeSet;
import dev.codespire.workspace.EmptyBundleException;
import dev.codespire.workspace.GitCredential;
import dev.codespire.workspace.PathGlob;
import dev.codespire.workspace.PublishRepo;
import dev.codespire.workspace.PushDecision;
import dev.codespire.workspace.PushGate;
import dev.codespire.workspace.PushRefusedException;
import dev.codespire.workspace.UnsafeTreePathException;
import org.eclipse.jgit.api.errors.GitAPIException;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/**
 * What happens to one bundle: fetch it, diff it, gate it, push it, say so.
 *
 * <p>Extracted from {@code PublisherMain} so it can be driven against a real local origin without a
 * container, an image or a daemon. The loop around it is trivial; this is the part with decisions
 * in it, and the part where getting the ORDER wrong is silent — gating after pushing would still
 * report a refusal, having already published the thing it refused.
 */
public final class PublishCycle {

    private final PublishRepo repo;

    private final String baseCommit;

    private final String branch;

    /** Compiled once here; PublisherConfig already refused a glob the gate cannot apply. */
    private final List<PathGlob> profile;

    private final long bundleMaxBytes;

    private final GitCredential credential;

    private final OutcomeWriter outcome;

    public PublishCycle(PublishRepo repo, String baseCommit, String branch, List<String> profileGlobs,
                        long bundleMaxBytes, GitCredential credential, OutcomeWriter outcome) {
        this.repo = repo;
        this.baseCommit = baseCommit;
        this.branch = branch;
        this.profile = PathGlob.compileAll(profileGlobs);
        this.bundleMaxBytes = bundleMaxBytes;
        this.credential = credential;
        this.outcome = outcome;
    }

    /**
     * @return whether the run may continue. False means the gate refused, and a gate trip
     *         TERMINATES the run — the next bundle would carry the same refused commit plus more.
     */
    public boolean handle(Path bundle) {
        ChangeSet changes;
        String sha;
        try {
            sha = repo.fetchBundle(bundle, bundleMaxBytes);
            changes = repo.changesSince(baseCommit, sha);
        } catch (GitAPIException | IOException | BundleTooLargeException | EmptyBundleException
                 | AmbiguousBundleException | UnsafeTreePathException e) {
            // A bundle that cannot be read is not a refusal and not a push. Reported and skipped:
            // the agent is still working, and the next checkpoint may be fine. Named rather than
            // caught as Exception so that a NEW failure mode surfaces as a crash to be classified,
            // not as one more "unreadable bundle" nobody looks at.
            outcome.failed("BUNDLE_UNREADABLE", e.getClass().getSimpleName() + ": " + e.getMessage());
            return true;
        }

        // The gate runs BEFORE the push, always. There is no ordering in which a refusal that
        // arrives after a push means anything.
        PushDecision decision = PushGate.decideCompiled(changes, profile);
        if (!decision.allowed()) {
            outcome.refused(decision.blocked(), changes.paths());
            return false;
        }

        try {
            outcome.pushed(repo.pushRef(sha, branch, credential), changes.paths());
            return true;
        } catch (GitAPIException | PushRefusedException e) {
            // PushRefusedException is the forge's own ruleset saying no, which JGit reports as a
            // status rather than throwing on its own. Never reported as a success.
            //
            // A branch that moved under the run is its own cause. Reported as PUSH_FAILED it points
            // an operator at the forge, and it is classified retryable — so the retry pushes the
            // same stale parent again and is refused identically. The remedy is to clone the branch
            // rather than the base commit, which is the resume work's job; never a force-push from
            // here, which would discard whatever moved the branch.
            String cause = e instanceof PushRefusedException refusal && refusal.isNonFastForward()
                    ? "NON_FAST_FORWARD"
                    : "PUSH_FAILED";
            outcome.failed(cause, e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }
}
