package dev.codespire.publisher;

import dev.codespire.workspace.GitCredential;
import dev.codespire.workspace.PublishRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

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
}
