package dev.codespire.publisher;

import dev.codespire.workspace.GitCredential;
import dev.codespire.workspace.PublishRepo;
import dev.codespire.workspace.WorkspaceClone;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The init clone of a land fix against a real local origin; {@code git} must be on PATH. */
class CloneMainTest {
    static final GitCredential CREDENTIAL = new GitCredential("TEST-user", "TEST-secret-value");
    @TempDir Path dir;

    @AfterEach void release() { PublishRepo.releaseAllPackWindows(); }

    /**
     * A land fix merges the pull request's base into the item branch, so the agent needs the base in its clone.
     * Removing {@code origin} deletes {@code refs/remotes/origin/*}; the base must survive under its own namespace.
     */
    @Test void theFetchedBaseSurvivesRemoteRemoval() throws Exception {
        GitFixture f = new GitFixture(dir);
        Path seed = f.root.resolve("seed");
        Files.writeString(seed.resolve("BASE.md"), "TEST base moved\n");
        f.run(seed, "git", "add", "-A");
        f.run(seed, "git", "commit", "-m", "TEST base moves");
        f.run(seed, "git", "push", "origin", "HEAD:refs/heads/TEST-main");
        String baseTip = f.out(seed, "git", "rev-parse", "HEAD");
        assertNotEquals(f.base, baseTip);

        Path ws = dir.resolve("ws");
        CloneMain.populate(f.originUri(), f.base, "spire/work-TEST", ws, CREDENTIAL, "TEST-user", null, "TEST-main");

        assertEquals(baseTip, f.out(ws, "git", "rev-parse", "--verify", "refs/spire-base/TEST-main"));
        assertEquals(f.base, f.out(ws, "git", "rev-parse", "HEAD"), "the branch still starts at the base commit");
        assertEquals("", f.out(ws, "git", "remote"), "the workspace keeps no remote");
    }

    @Test void withoutAFetchBranchNoBaseRefIsKept() throws Exception {
        GitFixture f = new GitFixture(dir);
        Path ws = dir.resolve("ws");
        CloneMain.populate(f.originUri(), f.base, "spire/work-TEST", ws, CREDENTIAL, "TEST-user", null, null);

        assertEquals("", f.out(ws, "git", "for-each-ref", "refs/spire-base/"));
    }

    /** A fork's head is on no branch; init reads it from the pull ref named in {@code SPIRE_FETCH_REF}. */
    @Test void theFetchRefReachesPopulate() throws Exception {
        GitFixture f = new GitFixture(dir);
        Path seed = f.root.resolve("seed");
        f.run(seed, "git", "checkout", "-b", "TEST-fork");
        Files.writeString(seed.resolve("FORK.md"), "TEST fork\n");
        f.run(seed, "git", "add", "-A");
        f.run(seed, "git", "commit", "-m", "TEST fork");
        String forkHead = f.out(seed, "git", "rev-parse", "HEAD");
        f.run(seed, "git", "push", "origin", "HEAD:refs/pull/7/head");

        Path ws = dir.resolve("ws");
        CloneMain.populate(new WorkspaceClone.CloneRequest(f.originUri(), forkHead, "spire/review-7", ws, CREDENTIAL,
                "TEST-user", "TEST-user@factory.invalid", null, "refs/pull/7/head"), null);

        assertEquals(forkHead, f.out(ws, "git", "rev-parse", "HEAD"));
        assertEquals("TEST fork", Files.readString(ws.resolve("FORK.md")).trim());
    }

    /** {@code run} reports a malformed ref as CLONE_FAILED, before any network call. */
    @Test void aBadFetchRefIsCloneFailed() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Map<String, String> env = Map.of(
                "SPIRE_REMOTE_URI", "https://forge.example.com/TEST/repo.git",
                "SPIRE_BRANCH", "spire/review-7",
                "SPIRE_BASE_COMMIT", "0123456789abcdef0123456789abcdef01234567",
                "SPIRE_CLONE_USERNAME", "TEST-user",
                "SPIRE_CLONE_SECRET", "TEST-secret-value",
                "SPIRE_WORKSPACE_DIR", dir.resolve("ws").toString(),
                "SPIRE_FETCH_REF", "refs/heads/main");

        int exit = CloneMain.run(env, new PrintStream(captured, true));

        assertEquals(1, exit);
        String text = captured.toString();
        assertTrue(text.contains("CLONE_FAILED"), text);
        assertTrue(text.contains("refs/heads/main"), text);
        assertFalse(Files.exists(dir.resolve("ws")));
    }
}
