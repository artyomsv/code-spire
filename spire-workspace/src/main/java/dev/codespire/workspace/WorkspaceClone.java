package dev.codespire.workspace;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.ResetCommand;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.api.errors.TransportException;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.RefUpdate;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.lib.StoredConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.function.LongConsumer;
import java.util.regex.Pattern;

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

    /**
     * The only ref init fetches besides the clone: a pull request's own head on GitHub or GitLab. Kept equal to
     * {@code PullRefs.PATTERN} in spire-contract (this module does not depend on it; a run-worker test asserts the
     * two strings are equal) and matched against the WHOLE string.
     */
    public static final String FETCH_REF_PATTERN = "refs/(pull|merge-requests)/[1-9][0-9]{0,17}/head";

    /** Where a fetched pull head is kept locally. */
    static final String PULL_HEAD_REF = "refs/spire-head/pull";

    private static final Pattern FETCH_REF = Pattern.compile(FETCH_REF_PATTERN);

    private static final int FETCH_ATTEMPTS = 3;

    private static final long FETCH_RETRY_MILLIS = 2000;

    /**
     * The pull ref fetch's transport timeout, in seconds. JGit's default is none, so a forge that accepts the connection
     * and then stalls would hold the init container until the run's own deadline; with this the attempt fails, and the
     * retry loop above gets its turn.
     */
    private static final int FETCH_TIMEOUT_SECONDS = 120;

    private WorkspaceClone() {
    }

    private static void sleepMillis(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting to refetch the pull ref", interrupted);
        }
    }

    /**
     * Everything one clone needs, so an entry point takes one value instead of nine arguments.
     *
     * @param baseCommit a commit id (full or abbreviated) reachable from some branch of the remote, or from
     *                   {@code fetchRef}
     * @param baseBranch the remote's branch to keep as {@value #BASE_REF_PREFIX}{@code <baseBranch>} (M4 slice 7a: a
     *                   land fix merges its pull request's base); null keeps nothing
     * @param fetchRef   a pull request's own head, fetched first for a fork whose head is on no branch; null fetches
     *                   nothing, anything but a pull request head ref is refused before any network call
     */
    public record CloneRequest(String remoteUri, String baseCommit, String branch, Path workspace,
                               GitCredential credential, String authorName, String authorEmail,
                               String baseBranch, String fetchRef) {
    }

    /**
     * A fork's head is on no branch of the base repository, so it is read from the pull request's own ref. The forge
     * may lag behind the webhook, so a head that is not there yet is refetched, 2 s apart, up to 3 tries in all.
     */
    private static void fetchPullRef(Git git, CloneRequest request, LongConsumer sleeper)
            throws GitAPIException, IOException {
        GitCredential credential = request.credential();
        String lastError = null;
        for (int attempt = 1; attempt <= FETCH_ATTEMPTS; attempt++) {
            try {
                git.fetch().setRemote("origin")
                        .setRefSpecs(new RefSpec("+" + request.fetchRef() + ":" + PULL_HEAD_REF))
                        .setCheckFetchedObjects(true)
                        .setTimeout(FETCH_TIMEOUT_SECONDS)
                        .setCredentialsProvider(new UsernamePasswordCredentialsProvider(
                                credential.username(), credential.secret()))
                        .call();
                lastError = null;
                ObjectId resolved = git.getRepository().resolve(request.baseCommit());
                if (resolved != null && git.getRepository().getObjectDatabase().has(resolved)) return;
            } catch (TransportException transport) {
                // A ref the forge has not created yet makes JGit throw ("Remote does not have <ref> available for
                // fetch"), which cannot be told from an unreachable host or a refused credential by type, so any
                // transport error uses an attempt; the last message is kept for the final failure.
                lastError = transport.getMessage();
            }
            if (attempt < FETCH_ATTEMPTS) sleeper.accept(FETCH_RETRY_MILLIS);
        }
        throw new IllegalArgumentException("base commit " + request.baseCommit() + " is not on " + request.fetchRef()
                + " after " + FETCH_ATTEMPTS + " fetches; the forge does not hold the pull request head"
                + (lastError == null ? "" : " (last error: " + lastError + ")"));
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
        populate(new CloneRequest(remoteUri, baseCommit, branch, workspace, credential, authorName, authorEmail,
                baseBranch, null));
    }

    /** The same from one request: the clone, an optional pull ref fetch, the base branch kept. */
    public static void populate(CloneRequest request) throws GitAPIException, IOException {
        populate(request, WorkspaceClone::sleepMillis);
    }

    /** The same with an injectable retry sleep, so a test does not wait. */
    static void populate(CloneRequest request, LongConsumer sleeper) throws GitAPIException, IOException {
        Objects.requireNonNull(request, "request");
        Objects.requireNonNull(request.remoteUri(), "remoteUri");
        String fetchRef = request.fetchRef();
        if (fetchRef != null && !FETCH_REF.matcher(fetchRef).matches()) {
            throw new IllegalArgumentException("fetch ref is not a pull request head ref: " + fetchRef);
        }
        GitCredential credential = Objects.requireNonNull(request.credential(), "credential");
        try (Git git = Git.cloneRepository()
                .setURI(request.remoteUri())
                .setDirectory(request.workspace().toFile())
                .setNoCheckout(true)
                .setCredentialsProvider(new UsernamePasswordCredentialsProvider(
                        credential.username(), credential.secret()))
                .call()) {
            if (fetchRef != null) fetchPullRef(git, request, sleeper);
            checkoutAndConfigure(git, request);
        }
    }

    /** Checks the base out on the new branch, sets the committer identity and drops the remote. */
    private static void checkoutAndConfigure(Git git, CloneRequest request) throws GitAPIException, IOException {
        // resolve() turns ANY full 40-hex string into an ObjectId without looking it up, so the
        // existence check has to be explicit; only an abbreviation is looked up on the way.
        ObjectId base = git.getRepository().resolve(request.baseCommit());
        if (base == null || !git.getRepository().getObjectDatabase().has(base)) {
            throw new IllegalArgumentException("base commit " + request.baseCommit()
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
        git.branchCreate().setName(request.branch()).setStartPoint(base.name()).setForce(true).call();
        git.getRepository().updateRef(Constants.HEAD).link(Constants.R_HEADS + request.branch());
        git.reset().setMode(ResetCommand.ResetType.HARD).call();

        StoredConfig config = git.getRepository().getConfig();
        config.setString("user", null, "name", request.authorName());
        config.setString("user", null, "email", request.authorEmail());
        config.save();

        if (request.baseBranch() != null) keepBase(git.getRepository(), request.baseBranch());
        git.remoteRemove().setRemoteName("origin").call();
    }
}
