package dev.codespire.workspace;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The checkpoint import against a real local origin and real git bundles; {@code git} must be on PATH. */
class CheckpointBundlesTest {
    static final GitCredential CREDENTIAL = new GitCredential("TEST-user", "TEST-secret");
    @TempDir Path dir;

    @AfterEach void release() { PublishRepo.releaseAllPackWindows(); }

    @Test void theWorkspaceEndsExactlyAtTheCheckpointHead() throws Exception {
        Fixture f = new Fixture(dir);
        f.commitAndBundle("one.txt", "TEST one");
        String head = f.agentHead();
        Path ws = f.cloneBase("verify");
        CheckpointBundles.importInto(ws, f.handoff, head, 1 << 20);
        assertEquals(head, f.out(ws, "git", "rev-parse", "HEAD"));
        assertTrue(Files.exists(ws.resolve("one.txt")));
        assertEquals("", f.out(ws, "git", "status", "--porcelain"));
    }

    @Test void aHeadAbsentFromTheNewestBundleResolvesFromAnOlderOne() throws Exception {
        Fixture f = new Fixture(dir);
        f.commitAndBundle("one.txt", "TEST one");
        String first = f.agentHead();
        f.rewriteAndBundle("two.txt", "TEST rewritten");
        assertNotEquals(first, f.agentHead());
        Path ws = f.cloneBase("verify");
        CheckpointBundles.importInto(ws, f.handoff, first, 1 << 20);
        assertEquals(first, f.out(ws, "git", "rev-parse", "HEAD"));
        assertFalse(Files.exists(ws.resolve("two.txt")));
    }

    @Test void anUnknownHeadIsCheckpointMissing() throws Exception {
        Fixture f = new Fixture(dir);
        f.commitAndBundle("one.txt", "TEST one");
        Path ws = f.cloneBase("verify");
        assertThrows(CheckpointMissingException.class, () -> CheckpointBundles.importInto(ws, f.handoff, "c".repeat(40), 1 << 20));
    }

    @Test void aSymlinkInTheHandoffIsNotFollowed() throws Exception {
        Fixture f = new Fixture(dir);
        f.commitAndBundle("one.txt", "TEST one");
        String head = f.agentHead();
        Path outside = f.handoff.resolveSibling("outside.bundle");
        Files.move(f.handoff.resolve("1.bundle"), outside);
        try {
            Files.createSymbolicLink(f.handoff.resolve("1.bundle"), outside);
        } catch (IOException | UnsupportedOperationException noPrivilege) {
            Assumptions.abort("this machine cannot create a symlink: " + noPrivilege.getMessage());
        }
        Path ws = f.cloneBase("verify");
        assertThrows(CheckpointMissingException.class, () -> CheckpointBundles.importInto(ws, f.handoff, head, 1 << 20));
    }

    /** A bare origin, an agent clone writing numbered bundles into a handoff directory, and a fresh base clone. */
    static final class Fixture {
        final Path root, bare, agent, handoff;
        final String base;
        int bundles;

        Fixture(Path root) throws Exception {
            this.root = root;
            bare = root.resolve("origin.git");
            Files.createDirectories(bare);
            run(bare, "git", "init", "--bare", "--initial-branch=main");
            Path seed = root.resolve("seed");
            Files.createDirectories(seed);
            run(seed, "git", "clone", bare.toUri().toString(), ".");
            identity(seed);
            Files.writeString(seed.resolve("README.md"), "TEST base\n");
            run(seed, "git", "add", "-A");
            run(seed, "git", "commit", "-m", "TEST base");
            run(seed, "git", "push", "origin", "main");
            base = out(seed, "git", "rev-parse", "HEAD");
            agent = root.resolve("agent");
            run(root, "git", "clone", bare.toUri().toString(), agent.toString());
            identity(agent);
            run(agent, "git", "checkout", "-b", "spire/TEST");
            handoff = Files.createDirectories(root.resolve("handoff"));
        }

        void commitAndBundle(String file, String content) throws Exception {
            Files.writeString(agent.resolve(file), content + "\n");
            run(agent, "git", "add", "-A");
            run(agent, "git", "commit", "-m", "TEST " + file);
            run(agent, "git", "bundle", "create", handoff.resolve(++bundles + ".bundle").toString(), base + "..HEAD");
        }

        void rewriteAndBundle(String file, String content) throws Exception {
            run(agent, "git", "reset", "--hard", base);
            commitAndBundle(file, content);
        }

        String agentHead() throws Exception { return out(agent, "git", "rev-parse", "HEAD"); }

        Path cloneBase(String name) throws Exception {
            Path ws = root.resolve(name);
            WorkspaceClone.populate(bare.toUri().toString(), base, name, ws, CREDENTIAL, "TEST", "TEST@factory.invalid");
            return ws;
        }

        private void identity(Path repo) throws Exception {
            run(repo, "git", "config", "user.email", "TEST@factory.invalid");
            run(repo, "git", "config", "user.name", "TEST");
        }

        void run(Path cwd, String... argv) throws Exception {
            Process p = new ProcessBuilder(argv).directory(cwd.toFile()).inheritIO().start();
            assertEquals(0, p.waitFor(), String.join(" ", argv));
        }

        String out(Path cwd, String... argv) throws Exception {
            Process p = new ProcessBuilder(argv).directory(cwd.toFile()).redirectErrorStream(true).start();
            String text = new String(p.getInputStream().readAllBytes()).trim();
            assertEquals(0, p.waitFor(), text);
            return text;
        }
    }
}
