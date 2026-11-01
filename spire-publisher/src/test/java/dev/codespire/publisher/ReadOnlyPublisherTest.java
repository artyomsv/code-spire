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
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code spire-publish-none}: a specification or plan run publishes nothing (A1, decision 4). Real git refs prove
 * the "never pushes" half; the outcome lines are what the run worker reads.
 */
class ReadOnlyPublisherTest {

    @TempDir
    Path root;

    private final ByteArrayOutputStream output = new ByteArrayOutputStream();

    private final PrintStream out = new PrintStream(output, true, StandardCharsets.UTF_8);

    @AfterEach
    void release() {
        PublishRepo.releaseAllPackWindows();
    }

    /** Even handed a full write configuration, the read-only executable leaves the origin untouched. */
    @Test
    void aReadOnlyPublisherNeverPushesABundle() throws Exception {
        GitFixture fixture = new GitFixture(root);
        fixture.commitAndBundle("TEST-edit.txt", "TEST edit during a specification run");
        Files.writeString(fixture.handoff.resolve(PublisherMain.DONE_SENTINEL), "");

        int exitCode = ReadOnlyPublisherMain.run(Map.of(
                "SPIRE_HANDOFF_DIR", fixture.handoff.toString(),
                "SPIRE_REMOTE_URI", fixture.originUri(),
                "SPIRE_BRANCH", "spire/TEST",
                "SPIRE_BRANCH_BASE", "main",
                "SPIRE_BASE_COMMIT", fixture.base,
                "SPIRE_GIT_USERNAME", "TEST",
                "SPIRE_GIT_SECRET", "TEST-secret"), out);

        assertEquals(0, exitCode, lines());
        assertEquals("", fixture.out(fixture.bare, "git", "for-each-ref", "refs/heads/spire"),
                "a read-only run must never create a branch on the origin");
        assertTrue(lines().contains("{\"event\":\"repository_changed\",\"bundles\":1}"), lines());
        assertFalse(lines().contains("\"event\":\"pushed\""), lines());
        assertFalse(lines().contains("\"event\":\"checkpoint\""), lines());
    }

    @Test
    void itReportsTheArtifactAndSummary() throws Exception {
        Path handoff = Files.createDirectory(root.resolve("handoff"));
        Files.writeString(handoff.resolve(HandoffFiles.ARTIFACT), "TEST-spec");
        Files.writeString(handoff.resolve(HandoffFiles.STEP_SUMMARY), "{\"decisions\":[\"TEST-decision\"]}");
        Files.writeString(handoff.resolve(PublisherMain.DONE_SENTINEL), "");

        assertEquals(0, ReadOnlyPublisherMain.run(Map.of("SPIRE_HANDOFF_DIR", handoff.toString()), out), lines());

        String artifact = "{\"event\":\"artifact\",\"text\":\"TEST-spec\"}";
        String summary = "{\"event\":\"step_summary\",\"json\":\"{\\\"decisions\\\":[\\\"TEST-decision\\\"]}\"}";
        assertTrue(lines().contains(artifact), lines());
        assertTrue(lines().indexOf(summary) > lines().indexOf(artifact), lines());
        assertFalse(lines().contains("repository_changed"), "nothing was committed: " + lines());
    }

    /** No remote, no branch, no credential: a missing write configuration is not a misconfiguration here. */
    @Test
    void itNeedsNoWriteCredential() throws Exception {
        Path handoff = Files.createDirectory(root.resolve("handoff"));
        Files.writeString(handoff.resolve(HandoffFiles.ARTIFACT), "TEST-spec");
        Files.writeString(handoff.resolve(PublisherMain.DONE_SENTINEL), "");

        assertEquals(0, ReadOnlyPublisherMain.run(Map.of("SPIRE_HANDOFF_DIR", handoff.toString()), out), lines());
        assertFalse(lines().contains("\"event\":\"failed\""), lines());
        assertTrue(lines().contains("\"event\":\"artifact\""), lines());
    }

    /** The run worker decides ARTIFACT_MISSING; the publisher only says what it refused. */
    @Test
    void aRefusedFileIsReportedNotFatal(@TempDir Path outside) throws Exception {
        Path handoff = Files.createDirectory(root.resolve("handoff"));
        Path target = Files.writeString(outside.resolve("TEST-secret.txt"), "TEST-secret");
        try {
            Files.createSymbolicLink(handoff.resolve(HandoffFiles.ARTIFACT), target);
        } catch (UnsupportedOperationException | java.io.IOException noSymlinks) {
            assumeTrue(false, "this host cannot create a symbolic link");
        }
        Files.writeString(handoff.resolve(PublisherMain.DONE_SENTINEL), "");

        assertEquals(0, ReadOnlyPublisherMain.run(Map.of("SPIRE_HANDOFF_DIR", handoff.toString()), out), lines());
        assertTrue(lines().contains(
                "{\"event\":\"handoff_file_refused\",\"file\":\"artifact.md\",\"why\":\"SYMLINK\"}"), lines());
        assertFalse(lines().contains("TEST-secret"), lines());
        assertFalse(lines().contains("\"event\":\"artifact\""), lines());
    }

    /**
     * A review run's unit names a 256 KiB cap (harness reviews, step 4): a 200 KiB answer is read under it and reported
     * whole, in parts, where a draft's 32 KiB cap would have refused it.
     */
    @Test
    void aReviewArtifactUnderItsUnitsCapIsReportedWhole() throws Exception {
        Path handoff = Files.createDirectory(root.resolve("handoff"));
        String answer = "TEST-finding line\n".repeat(200 * 1024 / 18);
        Files.writeString(handoff.resolve(HandoffFiles.ARTIFACT), answer);
        Files.writeString(handoff.resolve(PublisherMain.DONE_SENTINEL), "");

        assertEquals(0, ReadOnlyPublisherMain.run(Map.of("SPIRE_HANDOFF_DIR", handoff.toString(),
                "SPIRE_ARTIFACT_MAX_BYTES", "262144"), out), lines());

        assertFalse(lines().contains("handoff_file_refused"), lines().substring(0, Math.min(200, lines().length())));
        assertEquals(answer, ArtifactParts.join(lines()));
    }

    /** DONE means "everything I produced is here": a file read before it may still be written to. */
    @Test
    void itWaitsForDone() throws Exception {
        Path handoff = Files.createDirectory(root.resolve("handoff"));
        Files.writeString(handoff.resolve(HandoffFiles.ARTIFACT), "TEST-spec");
        AtomicInteger exitCode = new AtomicInteger(-1);
        Thread publisher = new Thread(() -> {
            try {
                exitCode.set(new ReadOnlyPublisher(handoff, new OutcomeWriter(out), 50).run());
            } catch (java.io.IOException | InterruptedException e) {
                exitCode.set(-2);
            }
        });
        publisher.start();

        Thread.sleep(2_000);
        assertEquals("", lines(), "nothing is read before DONE");
        Files.writeString(handoff.resolve(PublisherMain.DONE_SENTINEL), "");
        publisher.join(10_000);

        assertEquals(0, exitCode.get(), lines());
        assertTrue(lines().contains("{\"event\":\"artifact\",\"text\":\"TEST-spec\"}"), lines());
    }

    /** Defence in depth: the push cycle refuses the read-only policy, so no wiring mistake can push with it. */
    @Test
    void aPushCycleRefusesTheReadOnlyPolicy() {
        assertThrows(IllegalArgumentException.class, () -> new PublishCycle(null, "a".repeat(40), "spire/TEST",
                List.of(), 1L, null, new OutcomeWriter(out), PublicationPolicy.readOnly()));
    }

    private String lines() {
        return output.toString(StandardCharsets.UTF_8);
    }
}
