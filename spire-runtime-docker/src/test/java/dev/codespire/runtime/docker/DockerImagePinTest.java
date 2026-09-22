package dev.codespire.runtime.docker;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Which exact image a tag named when it was read (review of PR #167). Pure: the daemon's answers are
 * given, so every branch is reached without one.
 */
class DockerImagePinTest {

    private static final String DIGEST = "sha256:" + "a".repeat(64);
    private static final String OTHER = "sha256:" + "b".repeat(64);
    private static final String ID = "sha256:" + "c".repeat(64);

    @Test
    void aTagIsPinnedToTheRegistryDigestOfItsOwnRepository() {
        assertEquals("ghcr.io/TEST-org/agent@" + DIGEST, DockerRunRuntime.pinned("ghcr.io/TEST-org/agent:1.2",
                List.of("ghcr.io/TEST-org/other@" + OTHER, "ghcr.io/TEST-org/agent@" + DIGEST), ID));
    }

    /** "localhost:5000/agent" has a colon that is a port, not a tag. */
    @Test
    void aRegistryPortIsNotMistakenForATag() {
        assertEquals("localhost:5000/agent@" + DIGEST,
                DockerRunRuntime.pinned("localhost:5000/agent", List.of("localhost:5000/agent@" + DIGEST), ID));
    }

    @Test
    void aReferenceThatIsAlreadyADigestIsKept() {
        assertEquals("ghcr.io/TEST-org/agent@" + DIGEST,
                DockerRunRuntime.pinned("ghcr.io/TEST-org/agent@" + DIGEST, List.of(), ID));
    }

    /** A local build has no registry digest; its id runs here and fails to pull anywhere else. */
    @Test
    void anImageWithNoDigestOfItsOwnRepositoryIsPinnedByItsId() {
        assertEquals(ID, DockerRunRuntime.pinned("spire-agent-codex:latest", null, ID));
        assertEquals(ID, DockerRunRuntime.pinned("spire-agent-codex:latest", List.of("spire-agent-codex-old@" + OTHER), ID));
    }
}
