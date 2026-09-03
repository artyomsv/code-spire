package dev.codespire.workspace;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.channels.SeekableByteChannel;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The publisher's side of the handoff, against a real local origin and a real git bundle.
 *
 * <p>{@code git} must be on the test machine's PATH: the fixtures build the origin and the bundle
 * with it, standing in for the init container and the agent container. Production needs no git
 * binary — {@link PublishRepo} is JGit.
 */
class PublishRepoTest {

    /**
     * JGit keeps a pack open in a JVM-wide cache after the repository is closed, so @TempDir cannot
     * delete the clone directory. There is no per-repository purge in its public API — see
     * PublishRepo.releaseAllPackWindows.
     */
    @org.junit.jupiter.api.AfterEach
    void releasePackHandles() {
        PublishRepo.releaseAllPackWindows();
    }

    private void git(Path cwd, String... argv) throws Exception {
        Process p = new ProcessBuilder(argv).directory(cwd.toFile()).inheritIO().start();
        assertEquals(0, p.waitFor(), String.join(" ", argv));
    }

    private String rev(Path repo, String ref) throws Exception {
        Process p = new ProcessBuilder("git", "rev-parse", ref).directory(repo.toFile())
                .redirectErrorStream(true).start();
        String out = new String(p.getInputStream().readAllBytes()).trim();
        assertEquals(0, p.waitFor(), out);
        return out;
    }

    /** A bare origin plus a seed clone, standing in for the forge. No network. */
    private Path origin(Path dir) throws Exception {
        Path bare = dir.resolve("origin.git");
        Files.createDirectories(bare);
        git(bare, "git", "init", "--bare", "--initial-branch=main");

        Path seed = dir.resolve("seed");
        Files.createDirectories(seed);
        git(seed, "git", "clone", bare.toUri().toString(), ".");
        git(seed, "git", "config", "user.email", "t@t");
        git(seed, "git", "config", "user.name", "t");
        Files.writeString(seed.resolve("README.md"), "hello\n");
        Files.createDirectories(seed.resolve(".github/workflows"));
        Files.writeString(seed.resolve(".github/workflows/ci.yml"), "on: push\n");
        git(seed, "git", "add", "-A");
        git(seed, "git", "commit", "-m", "base");
        git(seed, "git", "push", "origin", "main");
        return bare;
    }

    /** Stands in for the AGENT container: its own clone, its own commits, its own bundle. */
    private Path agentBundle(Path dir, Path bare, String base) throws Exception {
        Path ws = dir.resolve("agent-ws");
        git(dir, "git", "clone", bare.toUri().toString(), ws.toString());
        git(ws, "git", "config", "user.email", "bot@spire");
        git(ws, "git", "config", "user.name", "spire-bot");
        git(ws, "git", "checkout", "-b", "spire/run_1");
        Files.writeString(ws.resolve("NEW.md"), "new\n");
        Files.delete(ws.resolve(".github/workflows/ci.yml"));
        Files.move(ws.resolve("README.md"), ws.resolve("DOCS.md"));
        git(ws, "git", "add", "-A");
        git(ws, "git", "commit", "-m", "agent work");

        Path bundle = dir.resolve("delta.bundle");
        git(ws, "git", "bundle", "create", bundle.toString(), base + "..HEAD");
        return bundle;
    }

    @Test
    void fetchesABundleAndSeesEveryChangedPath(@TempDir Path dir) throws Exception {
        Path bare = origin(dir);
        String base = rev(dir.resolve("seed"), "HEAD");
        Path bundle = agentBundle(dir, bare, base);

        try (PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                dir.resolve("publish"), null)) {
            String sha = repo.fetchBundle(bundle, 10_000_000L);

            Map<String, ChangeKind> changes = repo.changesSince(base, sha).paths().stream()
                    .collect(Collectors.toMap(ChangedPath::path, ChangedPath::kind, (a, b) -> a));

            assertEquals(ChangeKind.ADDED, changes.get("NEW.md"));
            assertEquals(ChangeKind.DELETED, changes.get(".github/workflows/ci.yml"),
                    "a deleted workflow must still be seen — deletion is a CI change");
            // A rename is reported on BOTH sides, and the KIND is asserted, not merely the path.
            // With JGit rename detection off — its default — a 100% rename arrives as an unrelated
            // ADD plus DELETE, so a presence-only assertion passes while the RENAME branch is dead
            // code and RENAMED_FROM / RENAMED_TO can never be produced at all. A gate that only asks
            // where a file came from is evaded by a rename INTO a protected path.
            assertEquals(ChangeKind.RENAMED_FROM, changes.get("README.md"), changes.toString());
            assertEquals(ChangeKind.RENAMED_TO, changes.get("DOCS.md"), changes.toString());
        }
    }

    @Test
    void aBranchThatMovedUnderTheRunIsItsOwnRefusal(@TempDir Path dir) throws Exception {
        // A resumed run, a human commit on spire/<subject>, or two replicas of one run: the remote's
        // branch has moved, and the forge rejects the push as non-fast-forward. Reported as a plain
        // push failure it points the operator at the forge, and it is retried -- which pushes the
        // same stale parent again. Told apart, it names the divergence and stops.
        Path bare = origin(dir);
        String base = rev(dir.resolve("seed"), "HEAD");
        Path bundle = agentBundle(dir, bare, base);

        // Someone else puts an unrelated commit on the branch this run is about to push to.
        Path other = dir.resolve("other");
        git(dir, "git", "clone", bare.toUri().toString(), other.toString());
        git(other, "git", "checkout", "-b", "spire/run_1");
        Files.writeString(other.resolve("moved.txt"), "a commit this run has never seen\n");
        git(other, "git", "add", ".");
        git(other, "git", "-c", "user.email=other@test", "-c", "user.name=Other", "commit", "-m", "move it");
        git(other, "git", "push", "origin", "spire/run_1");

        try (PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                dir.resolve("publish"), null)) {
            String sha = repo.fetchBundle(bundle, 10_000_000L);

            PushRefusedException refused = assertThrows(PushRefusedException.class,
                    () -> repo.pushRef(sha, "spire/run_1", null));

            assertTrue(refused.isNonFastForward(),
                    "a moved branch must be distinguishable from a transport fault: " + refused.refusals());
            assertFalse(refused.refusals().isEmpty(), "the forge's own words must survive for the detail");
        }
    }

    @Test
    void aRefusalThatIsNotADivergenceAnswersFalse() {
        // The other half of a one-sided property. The first version of this test asserted nothing
        // about it: it pushed to a fresh branch that SUCCEEDED, so no refusal was ever constructed
        // and isNonFastForward() was never called. Hardcoding the flag to true passed it, which is
        // the exact failure it was written to prevent.
        //
        // Driven by construction rather than through a real refusal, deliberately. A forge ruleset
        // refusal needs a server-side hook, and JGit's local transport implements receive-pack
        // in-process without running one — a pre-receive hook in the bare origin is simply never
        // invoked, so that test passed by pushing successfully and proved nothing a second time.
        // Both shapes the production code constructs for a non-divergence must answer false.
        assertFalse(new PushRefusedException(List.of("no ref update was attempted")).isNonFastForward());
        assertFalse(new PushRefusedException(List.of("origin: REJECTED_OTHER_REASON")).isNonFastForward());
    }

    @Test
    void pushesTheFetchedCommitToTheBranch(@TempDir Path dir) throws Exception {
        Path bare = origin(dir);
        String base = rev(dir.resolve("seed"), "HEAD");
        Path bundle = agentBundle(dir, bare, base);

        try (PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                dir.resolve("publish"), null)) {
            String sha = repo.fetchBundle(bundle, 10_000_000L);
            String pushed = repo.pushRef(sha, "spire/run_1", null);

            assertEquals("refs/heads/spire/run_1", pushed);
            assertEquals(sha, rev(bare, "refs/heads/spire/run_1"),
                    "the commit must be on the real remote");
        }
    }

    @Test
    void refusesABundleOverTheSizeCap(@TempDir Path dir) throws Exception {
        Path bare = origin(dir);
        String base = rev(dir.resolve("seed"), "HEAD");
        Path bundle = agentBundle(dir, bare, base);

        try (PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                dir.resolve("publish"), null)) {
            // An agent can write an object bomb; an unbounded read is a denial of service on the
            // publisher, which is the process holding the write credential.
            assertThrows(BundleTooLargeException.class, () -> repo.fetchBundle(bundle, 16L));
        }
    }

    @Test
    void neverCreatesAWorkingTree(@TempDir Path dir) throws Exception {
        Path bare = origin(dir);
        String base = rev(dir.resolve("seed"), "HEAD");
        Path bundle = agentBundle(dir, bare, base);

        Path publish = dir.resolve("publish");
        try (PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                publish, null)) {
            repo.fetchBundle(bundle, 10_000_000L);

            // Agent-authored content must never become a file on the publisher's disk (ADR-039).
            // A bare clone is what makes it safe for this process to hold a write credential.
            assertTrue(Files.notExists(publish.resolve("NEW.md")));
            assertTrue(Files.notExists(publish.resolve("DOCS.md")));
            assertTrue(Files.notExists(publish.resolve(".git")), "bare: no nested .git either");
        }
    }

    @Test
    void refusesABundleCarryingMoreThanOneBranch(@TempDir Path dir) throws Exception {
        Path bare = origin(dir);
        String base = rev(dir.resolve("seed"), "HEAD");

        Path ws = dir.resolve("agent-ws");
        git(dir, "git", "clone", bare.toUri().toString(), ws.toString());
        git(ws, "git", "config", "user.email", "bot@spire");
        git(ws, "git", "config", "user.name", "spire-bot");
        git(ws, "git", "checkout", "-b", "spire/run_1");
        Files.writeString(ws.resolve("A.md"), "a\n");
        git(ws, "git", "add", "-A");
        git(ws, "git", "commit", "-m", "wanted");
        git(ws, "git", "checkout", "-b", "spire/decoy");
        Files.writeString(ws.resolve("B.md"), "b\n");
        git(ws, "git", "add", "-A");
        git(ws, "git", "commit", "-m", "smuggled");

        Path bundle = dir.resolve("two.bundle");
        git(ws, "git", "bundle", "create", bundle.toString(),
                base + "..spire/run_1", base + "..spire/decoy");

        try (PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                dir.resolve("publish"), null)) {
            // "Take the first ref" is not a decision, it is an ordering accident. The publisher
            // gates and pushes ONE sha, so a bundle offering several must be refused rather than
            // silently resolved to whichever the ref database happened to list first.
            AmbiguousBundleException refused =
                    assertThrows(AmbiguousBundleException.class, () -> repo.fetchBundle(bundle, 10_000_000L));
            assertTrue(refused.getMessage().contains("spire/run_1"), refused.getMessage());
            assertTrue(refused.getMessage().contains("spire/decoy"), refused.getMessage());
        }
    }

    @Test
    void changesSinceReportsNothingWhenNothingChanged(@TempDir Path dir) throws Exception {
        Path bare = origin(dir);
        String base = rev(dir.resolve("seed"), "HEAD");

        try (PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                dir.resolve("publish"), null)) {
            // Guards the guard: a changesSince that reported everything would make the push gate
            // refuse every run, and a gate that always refuses is indistinguishable from one that
            // works until someone tries a legitimate change.
            assertTrue(repo.changesSince(base, base).isEmpty());
        }
    }

    @Test
    void aCredentialIsNeverRenderedIntoAString() {
        GitCredential credential = new GitCredential("spire-bot", "ghp_do-not-print");

        // A remote URI reaches the log stream, and an exception message reaches an operator's
        // timeline. A record's generated toString() would put the token in both.
        assertFalse(credential.toString().contains("ghp_do-not-print"), credential.toString());
        assertTrue(credential.toString().contains("spire-bot"), "the username is useful diagnostics");
    }

    @Test
    void everyChangeKindTheGateNeedsIsDistinguishable(@TempDir Path dir) throws Exception {
        // The push gate decides on paths AND kinds: deleting a CI file is as much a CI change as
        // adding one, and a rename must be refusable from either side.
        Set<ChangeKind> kinds = Set.of(ChangeKind.values());

        assertTrue(kinds.containsAll(Set.of(ChangeKind.ADDED, ChangeKind.MODIFIED, ChangeKind.DELETED,
                ChangeKind.RENAMED_FROM, ChangeKind.RENAMED_TO)));
    }
    @Test
    void aRefusedBundleWritesNoObjectIntoTheStore(@TempDir Path dir) throws Exception {
        Path bare = origin(dir);
        String base = rev(dir.resolve("seed"), "HEAD");
        Path bundle = agentBundle(dir, bare, base);
        String agentSha = rev(dir.resolve("agent-ws"), "HEAD");

        Path publish = dir.resolve("publish");
        try (PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                publish, null)) {
            assertThrows(BundleTooLargeException.class, () -> repo.fetchBundle(bundle, 16L));

            // Asserted against the OBJECT STORE, not through a diff. The version this replaces
            // asked whether changesSince(base, base) was empty — a commit compared against itself,
            // which is empty whatever the fetch did. Proven vacuous: moving the size check to AFTER
            // a real fetch, so the objects were genuinely written, left the test passing 10/10.
            assertFalse(repo.hasObject(agentSha),
                    "a refused bundle must leave the agent's commit out of the publisher's store");
        }
    }

    @Test
    void aBundleSwappedAfterTheSizeCheckIsStillRefused(@TempDir Path dir) throws Exception {
        // /handoff is read-write to the CONCURRENTLY RUNNING agent, and the handoff protocol
        // replaces a bundle by renaming over its name, which swaps the inode. Checking the size and
        // then re-opening the path is a time-of-check/time-of-use race: a small bundle passes, is
        // replaced, and the fetch reads the replacement. The cap is now enforced during a copy the
        // agent cannot reach, so the bytes counted are the bytes consumed.
        Path bare = origin(dir);
        String base = rev(dir.resolve("seed"), "HEAD");
        Path bundle = agentBundle(dir, bare, base);

        long realSize = Files.size(bundle);
        try (PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                dir.resolve("publish"), null)) {
            // A cap that the file's stat would satisfy, but its contents do not.
            assertThrows(BundleTooLargeException.class,
                    () -> repo.fetchBundle(bundle, realSize - 1));
        }
    }

    @Test
    void aHandoffPathThatIsNotARegularFileIsRefused(@TempDir Path dir) throws Exception {
        // The agent names this inode. JGit chooses its transport by inspecting the target, so a
        // directory that happens to be a git repository selects the LOCAL transport — which reads
        // agent-authored config and follows objects/info/alternates — instead of the bundle
        // transport.
        Path bare = origin(dir);
        Path decoy = dir.resolve("decoy.bundle");
        Files.createDirectories(decoy);

        try (PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                dir.resolve("publish"), null)) {
            assertThrows(IOException.class, () -> repo.fetchBundle(decoy, 10_000_000L));
        }
    }

    @Test
    void aRefusedPushIsReportedAsARefusal(@TempDir Path dir) throws Exception {
        // JGit's push().call() does NOT throw on rejection — it answers per-ref statuses that the
        // first version discarded, so a non-fast-forward, an auth failure and a pre-receive hook
        // refusal all returned the ref name as though the push had happened. That defeats the
        // forge-side ruleset RUN-TOPOLOGY §6.3 recommends as the second layer, and falsifies §5's
        // "a crash loses minutes": a run whose every checkpoint reported success while the branch
        // stood still loses all of it.
        Path bare = origin(dir);
        String base = rev(dir.resolve("seed"), "HEAD");
        Path bundle = agentBundle(dir, bare, base);

        // Someone else puts an unrelated commit on the target branch first.
        Path seed = dir.resolve("seed");
        git(seed, "git", "checkout", "-b", "spire/run_1");
        Files.writeString(seed.resolve("OTHER.md"), "other\n");
        git(seed, "git", "add", "-A");
        git(seed, "git", "commit", "-m", "someone else");
        git(seed, "git", "push", "origin", "spire/run_1");
        String theirs = rev(seed, "HEAD");

        try (PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                dir.resolve("publish"), null)) {
            String sha = repo.fetchBundle(bundle, 10_000_000L);

            PushRefusedException refused = assertThrows(PushRefusedException.class,
                    () -> repo.pushRef(sha, "spire/run_1", null));

            assertFalse(refused.refusals().isEmpty(), "a refusal must name what was refused");
            assertEquals(theirs, rev(bare, "refs/heads/spire/run_1"),
                    "and the remote must be exactly where it was");
        }
    }

    @Test
    void anEmptyBundleIsRefusedByTheGuardThatNamesIt(@TempDir Path dir) throws Exception {
        // The version this replaces asserted only Exception.class, and its fixture never reached
        // the guard at all — it died earlier in JGit's parser as "Short read of block". One extra
        // newline makes the bundle parseable and genuinely refless, which is the condition the
        // guard is about.
        Path bare = origin(dir);
        Path empty = dir.resolve("empty.bundle");
        Files.writeString(empty, "# v2 git bundle\n\n");

        try (PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                dir.resolve("publish"), null)) {
            EmptyBundleException refused = assertThrows(EmptyBundleException.class,
                    () -> repo.fetchBundle(empty, 10_000_000L));

            assertTrue(refused.getMessage().contains("incoming.bundle"), refused.getMessage());
        }
    }

    @Test
    void aMalformedBundleIsRefusedToo(@TempDir Path dir) throws Exception {
        Path bare = origin(dir);
        Path junk = dir.resolve("junk.bundle");
        Files.writeString(junk, "# v2 git bundle\n");

        try (PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                dir.resolve("publish"), null)) {
            assertThrows(Exception.class, () -> repo.fetchBundle(junk, 10_000_000L));
        }
    }

    @Test
    void aModifiedFileIsReportedAsModified(@TempDir Path dir) throws Exception {
        // MODIFIED was the one ChangeKind no fixture produced, so the arm mapping it was untested.
        Path bare = origin(dir);
        String base = rev(dir.resolve("seed"), "HEAD");

        Path ws = dir.resolve("agent-ws");
        git(dir, "git", "clone", bare.toUri().toString(), ws.toString());
        git(ws, "git", "config", "user.email", "bot@spire");
        git(ws, "git", "config", "user.name", "spire-bot");
        git(ws, "git", "checkout", "-b", "spire/run_1");
        Files.writeString(ws.resolve("README.md"), "hello, edited\n");
        git(ws, "git", "add", "-A");
        git(ws, "git", "commit", "-m", "edit");
        Path bundle = dir.resolve("m.bundle");
        git(ws, "git", "bundle", "create", bundle.toString(), base + "..HEAD");

        try (PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                dir.resolve("publish"), null)) {
            String sha = repo.fetchBundle(bundle, 10_000_000L);
            ChangeSet changes = repo.changesSince(base, sha);

            assertFalse(changes.isEmpty(), "a run that edited a file changed something");
            assertEquals(List.of(new ChangedPath("README.md", ChangeKind.MODIFIED)), changes.paths());
        }
    }

    @Test
    void theCloneDirectoryIsReportedBack(@TempDir Path dir) throws Exception {
        Path bare = origin(dir);
        Path publish = dir.resolve("publish");

        try (PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                publish, null)) {
            assertEquals(publish, repo.path());
        }
    }

    /**
     * The bundle is opened without following a symlink, in ONE operation.
     *
     * <p>The path is on {@code /handoff}, which the agent writes. Checking the attributes and
     * then opening are two syscalls, and {@code Files.newInputStream} FOLLOWS links — so a
     * rename-over between them made the publisher copy any file it can read, and its own
     * {@code /proc/self/environ} holds the git write token. Measured on Linux: the same symlink
     * that {@code newInputStream} reads through is refused by this open with
     * "Symbolic link loop (NOFOLLOW_LINKS specified)".
     *
     * <p>Skipped where the OS will not create a symlink — Windows needs a privilege this process
     * does not hold. The publisher runs on Linux, and CI runs on Linux, so the assertion is real
     * where it matters. It is a skip rather than a silent pass so a green Windows run cannot be
     * mistaken for evidence.
     */
    @Test
    void theBundleIsOpenedWithoutFollowingASymlink(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("publisher-environment");
        Files.writeString(secret, "SPIRE_GIT_SECRET=ghp_do-not-read-me");
        Path swapped = dir.resolve("incoming.bundle");
        try {
            Files.createSymbolicLink(swapped, secret);
        } catch (UnsupportedOperationException | java.nio.file.FileSystemException unavailable) {
            Assumptions.abort("this OS will not create a symlink here: " + unavailable.getMessage());
        }

        // The open ALONE, not the whole method: PublishRepo also checks the attributes first, so
        // routing through fetchBundle would pass with the open still following links and prove
        // nothing about the race this closes.
        assertThrows(IOException.class, () -> {
            try (SeekableByteChannel ignored = PublishRepo.openWithoutFollowing(swapped)) {
                throw new IllegalStateException("the symlink was opened");
            }
        });
    }
}
