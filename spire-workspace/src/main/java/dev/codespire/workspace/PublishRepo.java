package dev.codespire.workspace;

import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.diff.DiffFormatter;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.WindowCacheConfig;
import org.eclipse.jgit.transport.PushResult;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteRefUpdate;
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider;
import org.eclipse.jgit.util.io.DisabledOutputStream;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

/**
 * The publisher's own pristine clone of the repository.
 *
 * <p><b>It never reads the agent's workspace and never checks out a working tree.</b> Agent work
 * arrives only as a git bundle — objects and refs, carrying no config and no hooks — so nothing the
 * agent authored can execute here, and nothing it authored becomes a file on this disk. That is what
 * makes it safe for this process to hold a write credential (ADR-039).
 *
 * <p>That claim survived review, and is worth keeping. It is also not the whole risk: the shared
 * {@code /handoff} directory is writable by the running agent, so the bundle is a moving target
 * until it is copied out of the agent's reach — see {@link #fetchBundle}.
 */
public final class PublishRepo implements AutoCloseable {

    private static final String BUNDLE_REF = "refs/bundle/head";

    private static final String ORIGIN = "origin";

    /**
     * Rename detection compares every added blob against every deleted one, so it is quadratic in a
     * number an agent chooses. JGit's default limit is 400 (160,000 similarity scans) over blobs up
     * to {@code core.bigFileThreshold}, single-threaded, in a sidecar with a wall clock.
     */
    private static final int RENAME_LIMIT = 100;

    private static final int BIG_FILE_THRESHOLD_BYTES = 1 << 20;

    private final Git git;

    private final Path directory;

    private final Path privateStore;

    private PublishRepo(Git git, Path directory, Path privateStore) {
        this.git = git;
        this.directory = directory;
        this.privateStore = privateStore;
    }

    /** A BARE clone: no working tree exists, so none can be written into. */
    public static PublishRepo cloneBranch(String remoteUri, String branch, Path dir,
                                          GitCredential credential) throws GitAPIException, IOException {
        Git git = Git.cloneRepository()
                .setURI(remoteUri)
                .setDirectory(dir.toFile())
                .setBare(true)
                .setBranch(branch)
                .setCredentialsProvider(provider(credential))
                .call();
        return new PublishRepo(git, dir, Files.createTempDirectory("spire-publish-"));
    }

    /**
     * Copies the agent's bundle out of its reach, then fetches the single ref it offers.
     *
     * <p><b>The copy is the security boundary, not a convenience.</b> {@code /handoff} is
     * read-write to the concurrently running agent, and the handoff protocol replaces a bundle by
     * renaming over its name — which swaps the inode. Checking {@code Files.size()} and then opening
     * the path again is therefore a time-of-check/time-of-use race (CWE-367): a 16-byte bundle
     * passes the cap, is replaced with a 10 GB one, and the fetch reads the replacement. Copying
     * once, with the ceiling enforced <em>during</em> the copy, removes the race because the agent
     * cannot reach the copy.
     *
     * @return the sha the bundle's single ref resolved to
     * @throws BundleTooLargeException  during the copy, so an oversized bundle is never fully read
     * @throws AmbiguousBundleException when the bundle offers more than one ref
     */
    public String fetchBundle(Path bundle, long maxBytes) throws GitAPIException, IOException {
        Path copy = copyOutOfReach(bundle, maxBytes);
        String ref = soleRefOf(copy);

        git.fetch()
                .setRemote(copy.toAbsolutePath().toString())
                .setRefSpecs(new RefSpec("+" + ref + ":" + BUNDLE_REF))
                .call();

        Ref fetched = git.getRepository().getRefDatabase().exactRef(BUNDLE_REF);
        if (fetched == null || fetched.getObjectId() == null) {
            throw new IOException("fetching " + ref + " from the bundle wrote no ref");
        }
        return fetched.getObjectId().name();
    }

    /**
     * A private copy, refused the moment it exceeds the cap.
     *
     * <p>The path must be a REGULAR FILE, checked without following symlinks. The agent names this
     * inode, and JGit picks its transport by inspecting the target: a directory that happens to be a
     * git repository selects the local transport, which reads agent-authored config and follows
     * {@code objects/info/alternates}, instead of the bundle transport. A symlink points the read at
     * any path the publisher can reach.
     */
    private Path copyOutOfReach(Path bundle, long maxBytes) throws IOException {
        BasicFileAttributes attributes =
                Files.readAttributes(bundle, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IOException("handoff bundle is not a regular file: " + bundle.getFileName());
        }

        Path copy = privateStore.resolve("incoming.bundle");
        long copied = 0;
        byte[] buffer = new byte[8192];
        try (InputStream in = Files.newInputStream(bundle);
             OutputStream out = Files.newOutputStream(copy,
                     StandardOpenOption.CREATE,
                     StandardOpenOption.TRUNCATE_EXISTING,
                     StandardOpenOption.WRITE)) {
            int read;
            while ((read = in.read(buffer)) > 0) {
                copied += read;
                if (copied > maxBytes) {
                    // Refused DURING the read: an object bomb is never fully materialised, and the
                    // cap bounds bytes actually consumed rather than a size the agent could restate.
                    throw new BundleTooLargeException(copied, maxBytes);
                }
                out.write(buffer, 0, read);
            }
        }
        return copy;
    }

    /**
     * The one ref the bundle offers, refused before a single object is fetched.
     *
     * <p>A refspec cannot do this job. What a bundle advertises depends on how it was created:
     * {@code git bundle create f base..HEAD} names its ref {@code HEAD}, not
     * {@code refs/heads/<branch>}, so a {@code +refs/heads/*} refspec matches nothing — and
     * {@code fetch()} then returns NORMALLY with zero tracking updates, leaving an empty ref
     * database and no error to notice.
     */
    private String soleRefOf(Path bundle) throws GitAPIException, IOException {
        List<Ref> offered = new ArrayList<>(Git.lsRemoteRepository()
                .setRemote(bundle.toAbsolutePath().toString())
                .setHeads(false)
                .setTags(false)
                .call());
        offered.sort(Comparator.comparing(Ref::getName));

        if (offered.isEmpty()) {
            throw new EmptyBundleException(bundle.getFileName().toString());
        }
        if (offered.size() > 1) {
            // "Take the first ref" is not a decision — it is an ordering accident of the ref
            // database, and the branch that won would be the one nobody chose.
            throw new AmbiguousBundleException(offered.stream().map(Ref::getName).toList());
        }
        return offered.getFirst().getName();
    }

    /**
     * Every path changed between {@code baseCommit} and {@code sha}, renames on both sides.
     *
     * <p>Uses {@link DiffFormatter} rather than {@code git.diff()} because rename detection lives
     * there and nowhere else. <b>Rename detection is off by default, and without it the RENAME
     * branch is unreachable:</b> a 100% rename arrives as an unrelated ADD plus DELETE, so
     * {@link ChangeKind#RENAMED_FROM} and {@link ChangeKind#RENAMED_TO} could never be produced,
     * and the push gate's rename case would be testable only against fixtures no real diff can
     * generate. Measured, not assumed: with detection off, deleting the RENAME branch broke no test.
     *
     * <p>Every path is checked before it is returned. The fetch does not run {@code fsck}, so a tree
     * can carry {@code ..} segments, an absolute path, a backslash or a NUL — entries a glob written
     * as {@code .github/workflows/**} does not match but a checkout on the forge would honour. A
     * path the gate cannot reason about is refused here rather than judged there.
     */
    public ChangeSet changesSince(String baseCommit, String sha) throws IOException {
        Repository repo = git.getRepository();
        try (RevWalk walk = new RevWalk(repo);
             DiffFormatter diff = new DiffFormatter(DisabledOutputStream.INSTANCE)) {

            diff.setRepository(repo);
            diff.setDetectRenames(true);
            diff.getRenameDetector().setRenameLimit(RENAME_LIMIT);
            diff.getRenameDetector().setBigFileThreshold(BIG_FILE_THRESHOLD_BYTES);
            diff.getRenameDetector().setSkipContentRenamesForBinaryFiles(true);

            List<ChangedPath> paths = new ArrayList<>();
            for (DiffEntry entry : diff.scan(
                    walk.parseCommit(ObjectId.fromString(baseCommit)).getTree(),
                    walk.parseCommit(ObjectId.fromString(sha)).getTree())) {
                switch (entry.getChangeType()) {
                    case ADD -> paths.add(safe(entry.getNewPath(), ChangeKind.ADDED));
                    case MODIFY -> paths.add(safe(entry.getNewPath(), ChangeKind.MODIFIED));
                    case DELETE -> paths.add(safe(entry.getOldPath(), ChangeKind.DELETED));
                    case RENAME -> {
                        // BOTH sides. A rename INTO a protected path is the obvious evasion, and a
                        // rename OUT of one deletes it.
                        paths.add(safe(entry.getOldPath(), ChangeKind.RENAMED_FROM));
                        paths.add(safe(entry.getNewPath(), ChangeKind.RENAMED_TO));
                    }
                    // A COPY leaves the source untouched, so reporting RENAMED_FROM for it would
                    // assert a move that did not happen — and the gate refuses either side of a
                    // rename, so copying a workflow to docs/example.yml would kill a run that
                    // changed no CI at all. It is an addition, and only the new path is new.
                    case COPY -> paths.add(safe(entry.getNewPath(), ChangeKind.ADDED));
                }
            }
            return new ChangeSet(paths);
        }
    }

    /**
     * Pushes the gated sha, and fails when the forge refused it.
     *
     * <p><b>{@code PushCommand.call()} does not throw on rejection.</b> It answers per-ref
     * {@link RemoteRefUpdate.Status} values that the first version of this method discarded, so a
     * non-fast-forward, an authentication failure, and a pre-receive hook refusal all returned the
     * ref name as though the push had happened. That silently defeats the forge-side ruleset
     * RUN-TOPOLOGY §6.3 recommends as the second layer of defence — and it falsifies the
     * checkpointing guarantee, because a run whose every checkpoint reported success while the
     * branch stood still loses all of it when the pod dies.
     *
     * <p>Call ONLY after the push gate has passed.
     *
     * @return the pushed ref
     * @throws PushRefusedException when any ref update was refused, or when none was attempted
     */
    public String pushRef(String sha, String branch, GitCredential credential)
            throws GitAPIException {
        Iterable<PushResult> results = git.push()
                .setRemote(ORIGIN)
                .setRefSpecs(new RefSpec(sha + ":refs/heads/" + branch))
                .setCredentialsProvider(provider(credential))
                .call();

        List<String> refused = new ArrayList<>();
        boolean attempted = false;
        for (PushResult result : results) {
            for (RemoteRefUpdate update : result.getRemoteUpdates()) {
                attempted = true;
                if (!accepted(update.getStatus())) {
                    refused.add(update.getRemoteName() + ": " + update.getStatus()
                            + (update.getMessage() == null ? "" : " (" + update.getMessage() + ")"));
                }
            }
        }
        if (!attempted) {
            // An empty result is not a successful push; it is a push that never happened.
            throw new PushRefusedException(List.of("no ref update was attempted"));
        }
        if (!refused.isEmpty()) {
            throw new PushRefusedException(refused);
        }
        return "refs/heads/" + branch;
    }

    private static boolean accepted(RemoteRefUpdate.Status status) {
        return status == RemoteRefUpdate.Status.OK || status == RemoteRefUpdate.Status.UP_TO_DATE;
    }

    /**
     * A path safe for the push gate to reason about.
     *
     * <p>Refused rather than sanitised: a normalised path is a different path, and quietly matching
     * a glob against something the forge will not write is how a gate comes to disagree with the
     * repository it protects.
     */
    private static ChangedPath safe(String path, ChangeKind kind) {
        if (path == null || path.isBlank() || "/dev/null".equals(path)) {
            throw new UnsafeTreePathException(String.valueOf(path));
        }
        if (path.startsWith("/") || path.contains("\\") || path.indexOf('\0') >= 0
                || path.indexOf('\n') >= 0 || path.indexOf('\r') >= 0) {
            // git permits a newline in a path; the read model joins blocked paths on '\n', so one
            // would split into two on the way back out and misreport what the gate refused.
            throw new UnsafeTreePathException(path);
        }
        for (String segment : path.split("/", -1)) {
            if (segment.equals("..") || segment.equals(".")) {
                throw new UnsafeTreePathException(path);
            }
        }
        return new ChangedPath(path, kind);
    }

    /**
     * Whether an object is present in this repository's store.
     *
     * <p>Exists so a test can assert that a REFUSED bundle wrote nothing, which is the only honest
     * way to prove it: the assertion this replaced asked whether a diff of the base against itself
     * was empty, which is true whatever the fetch did.
     */
    public boolean hasObject(String sha) throws IOException {
        return git.getRepository().getObjectDatabase().has(ObjectId.fromString(sha));
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
     * Releases the repository, its open pack files, and the private bundle store.
     *
     * <p>{@code Git.close()} alone is not enough. Reading objects — which every push does — leaves
     * the pack open in JGit's object database, and the handle outlives the {@code Git} object. On
     * Linux that is an invisible descriptor leak; on Windows the directory then cannot be deleted at
     * all, which is how it was noticed.
     *
     * <p>Deliberately NOT {@code new WindowCacheConfig().install()}, which the first version used.
     * That reconfigures a single JVM-wide static cache: closing one repository would evict every
     * other repository's windows and silently discard any tuned configuration, unrecoverably. It
     * happened to be safe only because a publisher container handles one run.
     */
    @Override
    public void close() {
        Repository repo = git.getRepository();
        repo.getObjectDatabase().close();
        git.close();
        deleteRecursively(privateStore);
    }

    /**
     * Releases JGit's cached pack windows for EVERY repository in this JVM.
     *
     * <p>Separate from {@link #close()}, and named for what it really does, because JGit offers no
     * narrower option: {@code WindowCache} lives in an {@code internal} package whose only public
     * mutator is the static {@code reconfigure}, and its per-pack purge is package-private. So the
     * choice is a JVM-wide reset or a leaked descriptor, and hiding a JVM-wide reset inside
     * {@code close()} would mean one repository's cleanup silently evicting another's windows and
     * discarding any tuned configuration.
     *
     * <p>Call it when a process is finished with git entirely, or when the repository DIRECTORY must
     * be deletable afterwards. Closing the repository is not sufficient for that: reading objects
     * leaves the pack open in the cache, which on Linux is an invisible descriptor leak and on
     * Windows makes the directory undeletable — which is how it was found.
     *
     * <p>The publisher runs one repository per container, so calling this at exit costs nothing.
     */
    public static void releaseAllPackWindows() {
        new WindowCacheConfig().install();
    }

    private static void deleteRecursively(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (java.util.stream.Stream<Path> walk = Files.walk(root)) {
            Iterator<Path> deepestFirst = walk.sorted(Comparator.reverseOrder()).iterator();
            while (deepestFirst.hasNext()) {
                Files.deleteIfExists(deepestFirst.next());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("could not clear the private bundle store " + root, e);
        }
    }
}
