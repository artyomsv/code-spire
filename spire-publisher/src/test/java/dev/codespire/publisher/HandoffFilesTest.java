package dev.codespire.publisher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Every file on the handoff is the agent's: it chooses the name's target, its type and its size (A9, Review Focus 4).
 * The reader must never follow a link, never block on a pipe, and never hand on text it truncated.
 */
class HandoffFilesTest {

    @TempDir
    Path dir;

    @Test
    void aRegularFileIsRead() throws Exception {
        Files.writeString(dir.resolve(HandoffFiles.ARTIFACT), "TEST-spec");

        assertEquals(new HandoffFiles.Read.Found("TEST-spec"),
                HandoffFiles.read(dir, HandoffFiles.ARTIFACT, HandoffFiles.ARTIFACT_MAX_BYTES));
    }

    @Test
    void anAbsentFileIsAbsent() throws Exception {
        assertEquals(new HandoffFiles.Read.Absent(),
                HandoffFiles.read(dir, HandoffFiles.ARTIFACT, HandoffFiles.ARTIFACT_MAX_BYTES));
    }

    /** The target is made unreadable: following the link would throw AccessDenied, never return a refusal. */
    @Test
    void aSymlinkedArtifactIsRefusedWithoutFollowing(@TempDir Path outside) throws Exception {
        Path target = Files.writeString(outside.resolve("TEST-secret.txt"), "TEST-secret");
        try {
            Files.createSymbolicLink(dir.resolve(HandoffFiles.ARTIFACT), target);
        } catch (UnsupportedOperationException | java.io.IOException noSymlinks) {
            assumeTrue(false, "this host cannot create a symbolic link");
        }
        makeUnreadable(target);

        assertEquals(new HandoffFiles.Read.Refused(HandoffFiles.Refusal.SYMLINK),
                HandoffFiles.read(dir, HandoffFiles.ARTIFACT, HandoffFiles.ARTIFACT_MAX_BYTES));
    }

    /** Opening a FIFO with no writer blocks forever; a reader without the type check hangs the publisher. */
    @Test
    @Timeout(value = 5, unit = TimeUnit.SECONDS, threadMode = Timeout.ThreadMode.SEPARATE_THREAD)
    void aFifoIsRefused() throws Exception {
        assumeTrue(!System.getProperty("os.name").toLowerCase().contains("win"), "mkfifo is POSIX only");
        Process mkfifo = new ProcessBuilder("mkfifo", dir.resolve(HandoffFiles.ARTIFACT).toString()).start();
        assertEquals(0, mkfifo.waitFor());

        assertEquals(new HandoffFiles.Read.Refused(HandoffFiles.Refusal.NOT_REGULAR),
                HandoffFiles.read(dir, HandoffFiles.ARTIFACT, HandoffFiles.ARTIFACT_MAX_BYTES));
    }

    @Test
    void aDirectoryIsRefused() throws Exception {
        Files.createDirectory(dir.resolve(HandoffFiles.ARTIFACT));

        assertEquals(new HandoffFiles.Read.Refused(HandoffFiles.Refusal.NOT_REGULAR),
                HandoffFiles.read(dir, HandoffFiles.ARTIFACT, HandoffFiles.ARTIFACT_MAX_BYTES));
    }

    @Test
    void anOversizedArtifactIsRefused() throws Exception {
        Files.write(dir.resolve(HandoffFiles.ARTIFACT), "T".repeat(HandoffFiles.ARTIFACT_MAX_BYTES + 1)
                .getBytes(StandardCharsets.UTF_8));

        assertEquals(new HandoffFiles.Read.Refused(HandoffFiles.Refusal.TOO_LARGE),
                HandoffFiles.read(dir, HandoffFiles.ARTIFACT, HandoffFiles.ARTIFACT_MAX_BYTES));
    }

    /**
     * The size is judged from the attributes, before the file is opened: an oversized file the publisher may not
     * read is still refused as too large. Reading it first would throw AccessDenied instead, and the growth check in
     * {@code decode} alone would hide that, since it refuses any readable oversized file just as well.
     */
    @Test
    void anOversizedArtifactIsRefusedWithoutOpeningIt() throws Exception {
        Path artifact = Files.write(dir.resolve(HandoffFiles.ARTIFACT),
                "T".repeat(HandoffFiles.ARTIFACT_MAX_BYTES + 1).getBytes(StandardCharsets.UTF_8));
        makeUnreadable(artifact);
        assumeTrue(!Files.isReadable(artifact), "this user reads files whatever their permissions");

        assertEquals(new HandoffFiles.Read.Refused(HandoffFiles.Refusal.TOO_LARGE),
                HandoffFiles.read(dir, HandoffFiles.ARTIFACT, HandoffFiles.ARTIFACT_MAX_BYTES));
    }

    /** The other side of the cap: exactly the cap is a valid artifact, so the comparison is strict. */
    @Test
    void anArtifactOfExactlyTheCapIsRead() throws Exception {
        String text = "T".repeat(HandoffFiles.ARTIFACT_MAX_BYTES);
        Files.writeString(dir.resolve(HandoffFiles.ARTIFACT), text);

        assertEquals(new HandoffFiles.Read.Found(text),
                HandoffFiles.read(dir, HandoffFiles.ARTIFACT, HandoffFiles.ARTIFACT_MAX_BYTES));
    }

    /**
     * A file that grows between the size check and the read. A stream longer than the cap stands in for it, since
     * the attribute check already refuses a file that is large when looked at.
     */
    @Test
    void aFileThatGrowsWhileReadIsRefused() throws Exception {
        byte[] grown = "T".repeat(HandoffFiles.STEP_SUMMARY_MAX_BYTES + 1).getBytes(StandardCharsets.UTF_8);

        assertEquals(new HandoffFiles.Read.Refused(HandoffFiles.Refusal.TOO_LARGE),
                HandoffFiles.decode(new ByteArrayInputStream(grown), HandoffFiles.STEP_SUMMARY_MAX_BYTES));
    }

    @Test
    void invalidUtf8IsRefused() throws Exception {
        Files.write(dir.resolve(HandoffFiles.STEP_SUMMARY), new byte[] {(byte) 0xC3, (byte) 0x28});

        assertEquals(new HandoffFiles.Read.Refused(HandoffFiles.Refusal.NOT_UTF8),
                HandoffFiles.read(dir, HandoffFiles.STEP_SUMMARY, HandoffFiles.STEP_SUMMARY_MAX_BYTES));
    }

    @Test
    void multiByteTextIsReadAsWritten() throws Exception {
        Files.writeString(dir.resolve(HandoffFiles.STEP_SUMMARY), "{\"decisions\":[\"TEST-ü\"]}");

        assertEquals(new HandoffFiles.Read.Found("{\"decisions\":[\"TEST-ü\"]}"),
                HandoffFiles.read(dir, HandoffFiles.STEP_SUMMARY, HandoffFiles.STEP_SUMMARY_MAX_BYTES));
    }

    /** A review answer's unit hands the publisher a larger cap (harness reviews, step 4); it is read from the unit. */
    @Test
    void theArtifactCapIsReadFromTheUnit() {
        assertEquals(256 * 1024, HandoffFiles.artifactMaxBytes(java.util.Map.of("SPIRE_ARTIFACT_MAX_BYTES", "262144")));
    }

    /** A unit that names no cap is a draft's, or one from before the cap existed: it keeps 32 KiB. */
    @Test
    void anUnsetCapKeepsThirtyTwoKibibytes() {
        assertEquals(HandoffFiles.ARTIFACT_MAX_BYTES, HandoffFiles.artifactMaxBytes(java.util.Map.of()));
        assertEquals(32 * 1024, HandoffFiles.ARTIFACT_MAX_BYTES);
    }

    /** A cap that is not a positive byte count is refused by name, never read as 32 KiB or as no cap at all. */
    @Test
    void aMalformedCapIsRefused() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> HandoffFiles.artifactMaxBytes(java.util.Map.of("SPIRE_ARTIFACT_MAX_BYTES", "TEST")));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> HandoffFiles.artifactMaxBytes(java.util.Map.of("SPIRE_ARTIFACT_MAX_BYTES", "0")));
    }

    private static void makeUnreadable(Path target) throws java.io.IOException {
        try {
            Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("---------"));
        } catch (UnsupportedOperationException notPosix) {
            assumeTrue(false, "this host has no POSIX permissions");
        }
    }
}
