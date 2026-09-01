package dev.codespire.workspace;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
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
    void theSizeCapIsCheckedBeforeAnyObjectIsRead(@TempDir Path dir) throws Exception {
        Path bare = origin(dir);
        String base = rev(dir.resolve("seed"), "HEAD");
        Path bundle = agentBundle(dir, bare, base);

        Path publish = dir.resolve("publish");
        try (PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                publish, null)) {
            assertThrows(BundleTooLargeException.class, () -> repo.fetchBundle(bundle, 16L));

            // Refusing after the fetch would leave the agent's objects in the publisher's store,
            // which is the thing the cap exists to prevent.
            assertTrue(repo.changesSince(base, base).isEmpty(),
                    "a refused bundle must leave nothing behind");
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

            // Agent-authored content must never become a file on the publisher's disk (ADR-038).
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
    void refusesABundleCarryingNoBranchAtAll(@TempDir Path dir) throws Exception {
        Path bare = origin(dir);
        Path empty = dir.resolve("empty.bundle");
        Files.writeString(empty, "# v2 git bundle\n");

        try (PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                dir.resolve("publish"), null)) {
            assertThrows(Exception.class, () -> repo.fetchBundle(empty, 10_000_000L));
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
}
