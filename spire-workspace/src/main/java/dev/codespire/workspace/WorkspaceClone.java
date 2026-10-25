package dev.codespire.workspace;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Populates the agent's workspace: a working tree at an explicit base commit on a fresh branch
 * (RUN-TOPOLOGY §4). Runs in the init container, which holds the READ credential only.
 *
 * <p>Three things it does on purpose, beyond the clone:
 * <ul>
 *   <li><b>The branch starts at the base commit, not at the remote's head.</b> The command names a
 *       commit so the run is reproducible and the publisher's diff has a fixed floor; a head that
 *       moved between dispatch and clone would silently change both.</li>
 *   <li><b>The commit identity is written into the repository's own config</b>, so every commit the
 *       agent makes — and every autosave — is authored by the machine account without the image
 *       or the harness having to know its name.</li>
 *   <li><b>The remote is removed.</b> The agent holds no credential and could not push anyway; but a
 *       remote that is present invites a harness to try, and its failure output would carry the
 *       URL. Nothing the agent needs points at the forge.</li>
 * </ul>
 */
public final class WorkspaceClone {

    /** Where a kept base branch lives once the remote is gone; outside {@code refs/remotes} on purpose. */
    public static final String BASE_REF_PREFIX = "refs/spire-base/";

    private WorkspaceClone() {
    }

    private static void keepBase(Repository repository, String baseBranch) throws IOException {
        Ref tracked = repository.exactRef(Constants.R_REMOTES + "origin/" + baseBranch);
        if (tracked == null || tracked.getObjectId() == null) {
            throw new IllegalArgumentException("the remote has no branch " + baseBranch + " to merge from");
        }
        RefUpdate update = repository.updateRef(BASE_REF_PREFIX + baseBranch);
        update.setNewObjectId(tracked.getObjectId());
        RefUpdate.Result result = update.update();
        if (result != RefUpdate.Result.NEW && result != RefUpdate.Result.FORCED) {
            throw new IOException("could not keep the base branch " + baseBranch + ": " + result);
        }
    }

    /**
     * @param baseCommit a commit id (full or abbreviated) reachable from some branch of the remote
     * @throws IllegalArgumentException when the remote holds no such commit — the run cannot start
     *                                  from a floor the forge does not have
     */
    public static void populate(String remoteUri, String baseCommit, String branch, Path workspace,
                                GitCredential credential, String authorName, String authorEmail)
            throws GitAPIException, IOException {
        populate(remoteUri, baseCommit, branch, workspace, credential, authorName, authorEmail, null);
    }

    /**
     * The same, keeping the remote's {@code baseBranch} as {@value #BASE_REF_PREFIX}{@code <baseBranch>} (M4 slice
     * 7a). A land fix merges its pull request's base, and removing {@code origin} deletes every
     * {@code refs/remotes/origin/*} ref, so the base is copied out of that namespace first. Null keeps nothing.
     */
    public static void populate(String remoteUri, String baseCommit, String branch, Path workspace,
                                GitCredential credential, String authorName, String authorEmail, String baseBranch)
            throws GitAPIException, IOException {
        Objects.requireNonNull(remoteUri, "remoteUri");
        Objects.requireNonNull(credential, "credential");
        try (Git git = Git.cloneRepository()
                .setURI(remoteUri)
                .setDirectory(workspace.toFile())
                .setNoCheckout(true)
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(
                        credential.username(), credential.secret()))
                .call()) {
            // resolve() turns ANY full 40-hex string into an ObjectId without looking it up, so the
            // existence check has to be explicit; only an abbreviation is looked up on the way.
            ObjectId base = git.getRepository().resolve(baseCommit);
            if (base == null || !git.getRepository().getObjectDatabase().has(base)) {
                throw new IllegalArgumentException("base commit " + baseCommit
                        + " is not reachable from any branch of the remote");
            }
            // Point HEAD at the new branch FIRST, then fill the empty workspace from it.
            //
            // The obvious `checkout -b branch startPoint` cannot be used here, and the reason is not
            // obvious: the clone above left no working tree and an EMPTY INDEX, with HEAD still on
            // the remote's default branch. JGit's checkout is then a TWO-WAY merge from that
            // branch's tree to the target's, and a path present in both with different content reads
            // as a local edit it must not silently overwrite — so it refuses the whole checkout.
            //
            // Nothing catches this while the target is a commit ON the default branch, because then
            // the two trees agree. A FIX run is the case that is not: it starts at a pull request's
            // own head (ADR-040), so the first file that pull request EDITS conflicts. In production
            // that surfaced as the init container exiting 1 with CLONE_FAILED, naming the edited
            // file and not the added one beside it.
            //
            // `setForced(true)` does NOT fix it — in JGit that forces the ref update, not the
            // working-tree overwrite. Reset does: with HEAD already on the branch, it is a ONE-WAY
            // checkout that writes the index and work tree from the target, with no prior state to
            // conflict against. That is exactly the situation — the workspace is empty.
            git.branchCreate().setName(branch).setStartPoint(base.name()).setForce(true).call();
            git.getRepository().updateRef(Constants.HEAD).link(Constants.R_HEADS + branch);
            git.reset().setMode(ResetCommand.ResetType.HARD).call();

            StoredConfig config = git.getRepository().getConfig();
            config.setString("user", null, "name", authorName);
            config.setString("user", null, "email", authorEmail);
            config.save();

            if (baseBranch != null) keepBase(git.getRepository(), baseBranch);
            git.remoteRemove().setRemoteName("origin").call();
        }
    }
}
