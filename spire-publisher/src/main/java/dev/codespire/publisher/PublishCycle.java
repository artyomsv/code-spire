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
        } catch (PushRefusedException refusal) {
            // The forge answering no, which JGit reports as a per-ref status rather than by
            // throwing. A branch that moved under the run is its own cause: reported as a generic
            // push failure it sends an operator to the forge's ruleset, when the actual remedy is
            // to clone the branch rather than the base commit — the resume work's job. Never a
            // force-push from here, which would discard whatever moved the branch.
            outcome.failed(refusal.isNonFastForward() ? "NON_FAST_FORWARD" : "PUSH_REJECTED",
                    refusal.getClass().getSimpleName() + ": " + refusal.getMessage());
            return false;
        } catch (GitAPIException transport) {
            // The forge never answered. Retryable, unlike a refusal, because the same push may well
            // succeed — which is the answer CLONE_FAILED already gives for the identical condition
            // on the way in. Collapsing this into the refusal above said "never retry" for a
            // network fault.
            outcome.failed("PUSH_TRANSPORT_FAILED",
                    transport.getClass().getSimpleName() + ": " + transport.getMessage());
            return false;
        }
    }
}
