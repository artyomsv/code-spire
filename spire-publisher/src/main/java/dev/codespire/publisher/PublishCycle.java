package dev.codespire.publisher;

import dev.codespire.workspace.ChangeSet;
import dev.codespire.workspace.GitCredential;
import dev.codespire.workspace.PublishRepo;
import dev.codespire.workspace.PushDecision;
import dev.codespire.workspace.PushGate;

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

    private final List<String> profileGlobs;

    private final long bundleMaxBytes;

    private final GitCredential credential;

    private final OutcomeWriter outcome;

    public PublishCycle(PublishRepo repo, String baseCommit, String branch, List<String> profileGlobs,
                        long bundleMaxBytes, GitCredential credential, OutcomeWriter outcome) {
        this.repo = repo;
        this.baseCommit = baseCommit;
        this.branch = branch;
        this.profileGlobs = List.copyOf(profileGlobs);
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
        } catch (Exception e) {
            // A bundle that cannot be read is not a refusal and not a push. Reported and skipped:
            // the agent is still working, and the next checkpoint may be fine.
            outcome.failed("BUNDLE_UNREADABLE", e.getClass().getSimpleName() + ": " + e.getMessage());
            return true;
        }

        // The gate runs BEFORE the push, always. There is no ordering in which a refusal that
        // arrives after a push means anything.
        PushDecision decision = PushGate.decide(changes, profileGlobs);
        if (!decision.allowed()) {
            outcome.refused(decision.blocked(), changes.paths());
            return false;
        }

        try {
            outcome.pushed(repo.pushRef(sha, branch, credential), changes.paths());
            return true;
        } catch (Exception e) {
            // Includes PushRefusedException — the forge's own ruleset saying no, which JGit reports
            // as a status rather than throwing on its own. Never reported as a success.
            outcome.failed("PUSH_FAILED", e.getClass().getSimpleName() + ": " + e.getMessage());
            return false;
        }
    }
}
