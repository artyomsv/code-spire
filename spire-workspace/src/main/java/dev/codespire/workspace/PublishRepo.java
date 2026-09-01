package dev.codespire.workspace;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * The publisher's own pristine clone of the repository.
 *
 * <p><b>It never reads the agent's workspace and never checks out a working tree.</b> Agent work
 * arrives only as a git bundle — objects and refs, carrying no config and no hooks — so nothing the
 * agent authored can execute here, and nothing it authored becomes a file on this disk. That is what
 * makes it safe for this process to hold a write credential (ADR-038).
 */
public final class PublishRepo implements AutoCloseable {

    private static final String BUNDLE_REFS = "refs/bundle/";

    private final Git git;

    private final Path directory;

    private PublishRepo(Git git, Path directory) {
        this.git = git;
        this.directory = directory;
    }

    /** A BARE clone: no working tree exists, so none can be written into. */
    public static PublishRepo cloneBranch(String remoteUri, String branch, Path dir,
                                          GitCredential credential) throws Exception {
        Git git = Git.cloneRepository()
                .setURI(remoteUri)
                .setDirectory(dir.toFile())
                .setBare(true)
                .setBranch(branch)
                .setCredentialsProvider(provider(credential))
                .call();
        return new PublishRepo(git, dir);
    }

    /**
     * Fetches the agent's bundle into this repository's object store.
     *
     * @return the sha the bundle's single branch resolved to
     * @throws BundleTooLargeException  before any object is read, so a bomb leaves nothing behind
     * @throws AmbiguousBundleException when the bundle offers more than one branch
     */
    public String fetchBundle(Path bundle, long maxBytes) throws Exception {
        long size = Files.size(bundle);
        if (size > maxBytes) {
            // Checked BEFORE the fetch. Refusing afterwards would already have written the agent's
            // objects into the store, which is the thing the cap exists to prevent.
            throw new BundleTooLargeException(size, maxBytes);
        }
        String remote = bundle.toAbsolutePath().toString();

        // Ask what the bundle offers BEFORE fetching it, so both refusals below happen with none of
        // its objects in this store — the same reasoning as the size cap above.
        //
        // A refspec cannot do this job. What a bundle advertises depends on how it was created:
        // `git bundle create f base..HEAD` names its ref HEAD, not refs/heads/<branch>, so a
        // `+refs/heads/*:...` refspec matches nothing — and fetch() then returns NORMALLY with zero
        // tracking updates, leaving an empty ref database and no error to notice.
        List<Ref> offered = new ArrayList<>(Git.lsRemoteRepository()
                .setRemote(remote)
                .setHeads(false)
                .setTags(false)
                .call());
        offered.sort(Comparator.comparing(Ref::getName));

        if (offered.isEmpty()) {
            throw new IOException("bundle contained no ref: " + bundle.getFileName());
        }
        if (offered.size() > 1) {
            throw new AmbiguousBundleException(offered.stream().map(Ref::getName).toList());
        }

        Ref only = offered.getFirst();
        git.fetch()
                .setRemote(remote)
                .setRefSpecs(new RefSpec("+" + only.getName() + ":" + BUNDLE_REFS + "head"))
                .call();

        Ref fetched = git.getRepository().getRefDatabase().exactRef(BUNDLE_REFS + "head");
        if (fetched == null || fetched.getObjectId() == null) {
            throw new IOException("fetching " + only.getName() + " from the bundle wrote no ref");
        }
        return fetched.getObjectId().name();
    }

    /**
     * Every path changed between {@code baseCommit} and {@code sha}, renames on both sides.
     *
     * <p>Uses {@link DiffFormatter} rather than {@code git.diff()} because rename detection lives
     * there and nowhere else — {@code DiffCommand} has no {@code setDetectRenames}. That is not a
     * stylistic choice. <b>Rename detection is off by default, and without it the RENAME branch
     * below is unreachable:</b> a 100% rename arrives as an unrelated ADD plus DELETE, so
     * {@link ChangeKind#RENAMED_FROM} and {@link ChangeKind#RENAMED_TO} could never be produced,
     * and the push gate's rename case would be testable only against hand-built fixtures that no
     * real diff can generate. Measured, not assumed: with detection off, deleting the RENAME branch
     * entirely broke no test.
     *
     * <p>The gate is safe under either reading, since a rename INTO a protected path shows as ADDED
     * when detection is off and as RENAMED_TO when it is on. What detection buys is a change list
     * that describes what actually happened, and a {@link ChangeKind} contract that is true.
     */
    public ChangeSet changesSince(String baseCommit, String sha) throws IOException {
        Repository repo = git.getRepository();
        try (RevWalk walk = new RevWalk(repo);
             DiffFormatter diff = new DiffFormatter(DisabledOutputStream.INSTANCE)) {

            diff.setRepository(repo);
            diff.setDetectRenames(true);

            List<ChangedPath> paths = new ArrayList<>();
            for (DiffEntry entry : diff.scan(
                    walk.parseCommit(ObjectId.fromString(baseCommit)).getTree(),
                    walk.parseCommit(ObjectId.fromString(sha)).getTree())) {
                switch (entry.getChangeType()) {
                    case ADD -> paths.add(new ChangedPath(entry.getNewPath(), ChangeKind.ADDED));
                    case MODIFY -> paths.add(new ChangedPath(entry.getNewPath(), ChangeKind.MODIFIED));
                    case DELETE -> paths.add(new ChangedPath(entry.getOldPath(), ChangeKind.DELETED));
                    case RENAME, COPY -> {
                        // BOTH sides. A rename INTO a protected path is the obvious evasion.
                        paths.add(new ChangedPath(entry.getOldPath(), ChangeKind.RENAMED_FROM));
                        paths.add(new ChangedPath(entry.getNewPath(), ChangeKind.RENAMED_TO));
                    }
                }
            }
            return new ChangeSet(paths);
        }
    }

    /** Call ONLY after the push gate has passed. @return the pushed ref. */
    public String pushRef(String sha, String branch, GitCredential credential) throws Exception {
        git.push()
                .setRemote("origin")
                .setRefSpecs(new RefSpec(sha + ":refs/heads/" + branch))
                .setCredentialsProvider(provider(credential))
                .call();
        return "refs/heads/" + branch;
    }

    public Path path() {
        return directory;
    }

    private static UsernamePasswordCredentialsProvider provider(GitCredential credential) {
        return credential == null
                ? null
                : new UsernamePasswordCredentialsProvider(credential.username(), credential.secret());
    }

    /**
     * Releases the repository AND its open pack files.
     *
     * <p>{@code Git.close()} alone is not enough. Reading objects — which every push does — leaves
     * the pack open in JGit's shared window cache, and the handle outlives the {@code Git} object.
     * On Linux that is an invisible leak; on Windows the directory then cannot be deleted at all,
     * which is how it was noticed. Either way a process that handled several repositories would
     * accumulate descriptors it never closes.
     */
    @Override
    public void close() {
        Repository repo = git.getRepository();
        git.close();
        repo.close();
        // Evicts this repository's cached pack windows. Without it the pack file stays open even
        // after the repository's use count reaches zero.
        new WindowCacheConfig().install();
    }
}
