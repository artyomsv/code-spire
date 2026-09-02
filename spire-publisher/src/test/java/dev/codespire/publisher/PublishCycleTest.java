package dev.codespire.publisher;

import dev.codespire.workspace.PublishRepo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The publish cycle against a real local origin and a real bundle — no container, no daemon.
 *
 * <p>The properties under test are the ones whose failure is silent: that the gate runs BEFORE the
 * push and not merely somewhere in the method, and that a refusal stops the run rather than being
 * reported while the work goes out anyway.
 */
class PublishCycleTest {

    private final ByteArrayOutputStream captured = new ByteArrayOutputStream();

    private final OutcomeWriter outcome =
            new OutcomeWriter(new PrintStream(captured, true, StandardCharsets.UTF_8));

    @AfterEach
    void releasePackHandles() {
        PublishRepo.releaseAllPackWindows();
    }

    private String written() {
        return captured.toString(StandardCharsets.UTF_8);
    }

    private void git(Path cwd, String... argv) throws Exception {
        Process process = new ProcessBuilder(argv).directory(cwd.toFile()).inheritIO().start();
        assertEquals(0, process.waitFor(), String.join(" ", argv));
    }

    /**
     * The sha a ref points at, or empty when it does not exist.
     *
     * <p>Reads the EXIT CODE rather than the output. git rev-parse prints a diagnostic to stderr
     * and exits non-zero for a missing ref, so returning its output made "the branch must not
     * exist" assertions compare against an error message instead of an empty string — they failed
     * while the behaviour under test was correct.
     */
    private String rev(Path repo, String ref) throws Exception {
        Process process = new ProcessBuilder("git", "rev-parse", "--verify", "--quiet", ref)
                .directory(repo.toFile()).redirectErrorStream(true).start();
        String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
        return process.waitFor() == 0 ? out : "";
    }

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

    /** Stands in for the agent container: its own clone, its own commit, its own bundle. */
    private Path agentBundle(Path dir, Path bare, String base, String script) throws Exception {
        Path ws = dir.resolve("agent-ws");
        git(dir, "git", "clone", bare.toUri().toString(), ws.toString());
        git(ws, "git", "config", "user.email", "bot@spire");
        git(ws, "git", "config", "user.name", "spire-bot");
        git(ws, "git", "checkout", "-b", "spire/run_1");
        Process process = new ProcessBuilder("sh", "-c", script).directory(ws.toFile()).inheritIO().start();
        assertEquals(0, process.waitFor(), script);
        git(ws, "git", "add", "-A");
        git(ws, "git", "commit", "-m", "agent work");
        Path bundle = dir.resolve("1.bundle");
        git(ws, "git", "bundle", "create", bundle.toString(), base + "..HEAD");
        return bundle;
    }

    private PublishCycle cycle(PublishRepo repo, String base) {
        return new PublishCycle(repo, base, "spire/run_1", List.of(), 10_000_000L, null, outcome);
    }

    @Test
    void aBranchThatMovedIsReportedAsItsOwnCauseNotAsAGenericPushFailure(@TempDir Path dir) throws Exception {
        // The publisher's half of the mapping had no test at all: the whole push-refusal catch was
        // unreached by this class, so both the new NON_FAST_FORWARD branch and the pre-existing one
        // were unexercised. The workspace half is tested against a real origin; this is what turns
        // that flag into the word an operator actually reads.
        Path bare = origin(dir);
        String base = rev(dir.resolve("seed"), "HEAD");
        Path bundle = agentBundle(dir, bare, base, "echo new > NEW.md");

        // Someone else moves the branch this run is about to push to.
        Path other = dir.resolve("other");
        git(dir, "git", "clone", bare.toUri().toString(), other.toString());
        git(other, "git", "checkout", "-b", "spire/run_1");
        Files.writeString(other.resolve("moved.txt"), "a commit this run has never seen\n");
        git(other, "git", "add", ".");
        git(other, "git", "-c", "user.email=other@test", "-c", "user.name=Other", "commit", "-m", "move it");
        git(other, "git", "push", "origin", "spire/run_1");

        try (PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                dir.resolve("publish"), null)) {
            assertFalse(cycle(repo, base).handle(bundle), "a refused push ends the run");
        }

        assertTrue(written().contains("\"cause\":\"NON_FAST_FORWARD\""), written());
        assertFalse(written().contains("\"event\":\"pushed\""),
                "a refusal must never also report a push");
    }

    @Test
    void pushesAnOrdinaryChangeAndSaysWhatItPushed(@TempDir Path dir) throws Exception {
        Path bare = origin(dir);
        String base = rev(dir.resolve("seed"), "HEAD");
        Path bundle = agentBundle(dir, bare, base, "echo new > NEW.md");

        try (PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                dir.resolve("publish"), null)) {
            assertTrue(cycle(repo, base).handle(bundle), "an allowed push lets the run continue");
        }

        assertTrue(written().contains("\"event\":\"pushed\""), written());
        assertTrue(written().contains("NEW.md"), written());
        assertFalse(rev(bare, "refs/heads/spire/run_1").isEmpty(),
                "and the commit is really on the remote");
    }

    @Test
    void refusesACiChangeAndDoesNotPushIt(@TempDir Path dir) throws Exception {
        // The property that matters is not that a refusal is REPORTED — it is that nothing reached
        // the remote. A gate that ran after the push would produce identical output.
        Path bare = origin(dir);
        String base = rev(dir.resolve("seed"), "HEAD");
        Path bundle = agentBundle(dir, bare, base, "echo 'on: push' > .github/workflows/evil.yml");

        try (PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                dir.resolve("publish"), null)) {
            assertFalse(cycle(repo, base).handle(bundle), "a gate trip terminates the run");
        }

        assertTrue(written().contains("\"event\":\"gate_refused\""), written());
        assertTrue(written().contains(".github/workflows/evil.yml"), written());
        assertEquals("", rev(bare, "refs/heads/spire/run_1"),
                "the branch must not exist on the remote at all");
    }

    @Test
    void aRefusalSaysWhatHappenedToEachBlockedFile(@TempDir Path dir) throws Exception {
        Path bare = origin(dir);
        String base = rev(dir.resolve("seed"), "HEAD");
        Path bundle = agentBundle(dir, bare, base, "rm .github/workflows/ci.yml");

        try (PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                dir.resolve("publish"), null)) {
            assertFalse(cycle(repo, base).handle(bundle));
        }

        // Deleting CI is as much a CI change as editing it, and the operator needs to know which.
        assertTrue(written().contains("\"kind\":\"DELETED\""), written());
    }

    @Test
    void anUnreadableBundleIsReportedAndTheRunContinues(@TempDir Path dir) throws Exception {
        // Not a refusal and not a push. The agent is still working and the next checkpoint may be
        // fine, so this must not terminate the run — but it must also never be silent.
        Path bare = origin(dir);
        String base = rev(dir.resolve("seed"), "HEAD");
        Path junk = dir.resolve("1.bundle");
        Files.writeString(junk, "not a bundle at all");

        try (PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                dir.resolve("publish"), null)) {
            assertTrue(cycle(repo, base).handle(junk));
        }

        assertTrue(written().contains("\"cause\":\"BUNDLE_UNREADABLE\""), written());
    }

    @Test
    void anOversizedBundleIsRefusedWithoutBeingRead(@TempDir Path dir) throws Exception {
        Path bare = origin(dir);
        String base = rev(dir.resolve("seed"), "HEAD");
        Path bundle = agentBundle(dir, bare, base, "echo new > NEW.md");

        try (PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                dir.resolve("publish"), null)) {
            PublishCycle tiny = new PublishCycle(repo, base, "spire/run_1", List.of(), 16L, null, outcome);

            assertTrue(tiny.handle(bundle), "a bomb is a bad bundle, not a gate refusal");
        }

        assertTrue(written().contains("BUNDLE_UNREADABLE"), written());
        assertEquals("", rev(bare, "refs/heads/spire/run_1"));
    }

    @Test
    void aProfileGlobProtectsMoreThanTheFloor(@TempDir Path dir) throws Exception {
        Path bare = origin(dir);
        String base = rev(dir.resolve("seed"), "HEAD");
        Path bundle = agentBundle(dir, bare, base, "mkdir -p deploy && echo x > deploy/values.yaml");

        try (PublishRepo repo = PublishRepo.cloneBranch(bare.toUri().toString(), "main",
                dir.resolve("publish"), null)) {
            PublishCycle narrowed = new PublishCycle(repo, base, "spire/run_1",
                    List.of("deploy/**"), 10_000_000L, null, outcome);

            assertFalse(narrowed.handle(bundle));
        }

        assertTrue(written().contains("deploy/values.yaml"), written());
        assertEquals("", rev(bare, "refs/heads/spire/run_1"));
    }
}
