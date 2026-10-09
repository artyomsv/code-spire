package dev.codespire.publisher;

import dev.codespire.workspace.GitCredential;
import dev.codespire.workspace.PublishRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** The verify init and the checkpoint clone against a real local origin; {@code git} must be on PATH. */
class VerifyPrepareTest {
    static final String SECRET = "TEST-secret-value";
    static final GitCredential CREDENTIAL = new GitCredential("TEST-user", SECRET);
    @TempDir Path dir;
    final ByteArrayOutputStream captured = new ByteArrayOutputStream();
    final OutcomeWriter outcome = new OutcomeWriter(new PrintStream(captured, true, StandardCharsets.UTF_8), "TEST-user", SECRET);

    @AfterEach void release() { PublishRepo.releaseAllPackWindows(); }
    String output() { return captured.toString(StandardCharsets.UTF_8); }

    @Test void preparesTheCheckpointAndReportsItsHead() throws Exception {
        GitFixture f = new GitFixture(dir);
        f.commitAndBundle("one.txt", "TEST one");
        String head = f.agentHead();
        Path ws = dir.resolve("ws");
        assertEquals(VerifyPrepareMain.PREPARED, VerifyPrepareMain.prepare(f.originUri(), f.base, head, CREDENTIAL, ws, f.handoff, 1 << 20, outcome));
        assertTrue(output().contains("\"event\":\"prepared\",\"head\":\"" + head + "\""), output());
        assertEquals(head, f.out(ws, "git", "rev-parse", "HEAD"));
        assertEquals("", f.out(ws, "git", "remote"), "the verify workspace keeps no remote");
    }

    @Test void aMissingCheckpointExitsThree() throws Exception {
        GitFixture f = new GitFixture(dir);
        f.commitAndBundle("one.txt", "TEST one");
        assertEquals(VerifyPrepareMain.CHECKPOINT_MISSING,
                VerifyPrepareMain.prepare(f.originUri(), f.base, "c".repeat(40), CREDENTIAL, dir.resolve("ws"), f.handoff, 1 << 20, outcome));
        assertTrue(output().contains("CHECKPOINT_MISSING"), output());
    }

    @Test void theCloneSecretNeverReachesTheOutput() throws Exception {
        GitFixture f = new GitFixture(dir);
        String unreachable = dir.resolve("absent-" + SECRET).toUri().toString();
        assertEquals(VerifyPrepareMain.FAILED,
                VerifyPrepareMain.prepare(unreachable, f.base, "c".repeat(40), CREDENTIAL, dir.resolve("ws"), f.handoff, 1 << 20, outcome));
        assertTrue(output().contains("VERIFY_PREPARE_FAILED"), output());
        assertFalse(output().contains(SECRET), output());
    }

    @Test void aMissingVariableIsMisconfigured() {
        assertEquals(VerifyPrepareMain.MISCONFIGURED, VerifyPrepareMain.run(Map.of(), new PrintStream(captured, true, StandardCharsets.UTF_8)));
        assertTrue(output().contains("PUBLISHER_MISCONFIGURED"), output());
    }

    @Test void aRetriedBuildClonesOntoItsBranchAtTheCheckpoint() throws Exception {
        GitFixture f = new GitFixture(dir);
        f.commitAndBundle("one.txt", "TEST one");
        String head = f.agentHead();
        Path ws = dir.resolve("retry");
        CloneMain.populate(f.originUri(), f.base, "spire/work-TEST-retry", ws, CREDENTIAL, "TEST-user",
                new CloneMain.Checkpoint(head, f.handoff, 1 << 20));
        assertEquals(head, f.out(ws, "git", "rev-parse", "HEAD"));
        assertEquals("spire/work-TEST-retry", f.out(ws, "git", "rev-parse", "--abbrev-ref", "HEAD"));
        assertTrue(Files.exists(ws.resolve("one.txt")));
    }

    @Test void aBuildWithoutACheckpointStartsFromTheBase() throws Exception {
        GitFixture f = new GitFixture(dir);
        Path ws = dir.resolve("fresh");
        CloneMain.populate(f.originUri(), f.base, "spire/work-TEST", ws, CREDENTIAL, "TEST-user", null);
        assertEquals(f.base, f.out(ws, "git", "rev-parse", "HEAD"));
    }
}
