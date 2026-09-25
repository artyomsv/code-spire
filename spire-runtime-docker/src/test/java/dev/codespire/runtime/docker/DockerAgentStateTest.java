package dev.codespire.runtime.docker;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Which Docker states may still hold a running agent (M3.5 part F). A signed-in seat is freed only on
 * "not running", so every state that is not certainly stopped must read as running.
 */
class DockerAgentStateTest {

    @Test
    void onlyAStoppedOrNeverStartedContainerIsNotRunning() {
        assertFalse(DockerRunRuntime.mayBeRunning("exited"));
        assertFalse(DockerRunRuntime.mayBeRunning("dead"));
        assertFalse(DockerRunRuntime.mayBeRunning("created"));
    }

    @Test
    void everythingElseMayStillRun() {
        assertTrue(DockerRunRuntime.mayBeRunning("running"));
        assertTrue(DockerRunRuntime.mayBeRunning("paused"));
        assertTrue(DockerRunRuntime.mayBeRunning("restarting"));
        assertTrue(DockerRunRuntime.mayBeRunning("removing"));
        assertTrue(DockerRunRuntime.mayBeRunning(null), "an unreported state is not proof of a stop");
    }
}
