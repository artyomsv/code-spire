package dev.codespire.runtime.docker;

import dev.codespire.runtime.RegistryCredential;
import org.junit.jupiter.api.Test;

import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The parts of the runtime that need no daemon.
 *
 * <p>{@link DockerRunRuntimeIT} covers the daemon; its ids are synthetic and slash-free, which is
 * exactly why the volume-name defect it guards against here never showed there.
 */
class DockerRunRuntimeTest {

    /** What the daemon accepts for a local volume name, from its own error message. */
    private static final Pattern DAEMON_LEGAL = Pattern.compile("[a-zA-Z0-9][a-zA-Z0-9_.-]*");

    /** A REAL run id: derived from a repository coordinate, so it carries '::', ':' and '/'. */
    private static final String RUN_ID = "run::github:acme/app:finding-1:1";

    @Test
    void aVolumeNameIsLegalForTheDaemonWhateverTheRunIdContains() {
        String name = DockerRunRuntime.volumeName(RUN_ID, "work");
        assertTrue(DAEMON_LEGAL.matcher(name).matches(), name);
        assertTrue(name.endsWith("-work"), "the volume's role stays readable at the end: " + name);
    }

    @Test
    void theNameIsStableForOneRunAndDistinctAcrossRuns() {
        // Stable, because destroy and orphan discovery find the volume by its label, but a retry
        // must not create a second one beside the first. Distinct, because two runs sharing a name
        // would share a work tree.
        assertEquals(DockerRunRuntime.volumeName(RUN_ID, "work"), DockerRunRuntime.volumeName(RUN_ID, "work"));
        assertNotEquals(DockerRunRuntime.volumeName(RUN_ID, "work"),
                DockerRunRuntime.volumeName("run::github:acme/app:finding-1:2", "work"));
        assertNotEquals(DockerRunRuntime.volumeName(RUN_ID, "work"), DockerRunRuntime.volumeName(RUN_ID, "out"));
    }

    /**
     * This arm refuses to steer, and the refusal is the implementation.
     *
     * <p>Asserted here because nothing else reaches it: the control listener refuses first on the
     * harness's declared capability, and no shipped harness declares steering, so the whole
     * delivery path is unreachable on a real deployment. Its own test stubbed a fake runtime that
     * threw, which proves the listener handles the throw and not that this arm produces one --
     * giving the method an empty body failed nothing anywhere.
     *
     * <p>A quiet no-op here would let an operator's instruction vanish while every layer above
     * reported success, which is the failure the SPI method was deliberately given no default to
     * prevent.
     */
    @Test
    void steeringIsRefusedRatherThanSilentlyIgnored() {
        DockerRunRuntime runtime = new DockerRunRuntime();

        UnsupportedOperationException refused = assertThrows(UnsupportedOperationException.class,
                () -> runtime.steer(new dev.codespire.runtime.RunHandle(RUN_ID, "unit-1"), "try again"));

        assertTrue(refused.getMessage().contains(RUN_ID),
                "the run must be named, or an operator cannot tell which instruction went nowhere");
    }
    /**
     * A private credential is offered only to the registry it was issued for.
     *
     * <p>The parse follows the daemon own rule: a first path segment is a registry only when it
     * carries a dot or a colon, or is localhost. Without that rule {@code acme/app} would parse as
     * the registry {@code acme}, a credential for a real host would match nothing, and every
     * private pull would silently fall back to anonymous and report the image as not found.
     */
    @Test
    void anImageReferenceResolvesToTheRegistryTheDaemonWouldUse() {
        assertEquals(DockerRunRuntime.DOCKER_HUB, DockerRunRuntime.registryHostOf("alpine:3.20"));
        assertEquals(DockerRunRuntime.DOCKER_HUB, DockerRunRuntime.registryHostOf("acme/app:1"),
                "a two-segment name is a Hub namespace, not a host");
        assertEquals("registry.acme.example",
                DockerRunRuntime.registryHostOf("registry.acme.example/team/app:1"));
        assertEquals("localhost:5000", DockerRunRuntime.registryHostOf("localhost:5000/app:1"));
        assertEquals("ghcr.io",
                DockerRunRuntime.registryHostOf("ghcr.io/acme/app@sha256:" + "0".repeat(64)));
    }
    /**
     * A corporate password is never presented to a registry it was not issued for.
     *
     * <p>The security half of the match. Offering the credential to every pull would be simpler
     * and would send it to whichever public registry an operator happened to reference -- a
     * password handed to a third party by a name in a config file.
     */
    @Test
    void aRegistryCredentialIsOfferedOnlyToItsOwnRegistry() {
        DockerRunRuntime runtime = new DockerRunRuntime(
                new RegistryCredential("registry.acme.example", "spire", "TEST-registry-secret"));

        assertTrue(runtime.authFor("registry.acme.example/team/agent:1").isPresent());
        assertTrue(runtime.authFor("REGISTRY.ACME.EXAMPLE/team/agent:1").isPresent(),
                "a registry host is case-insensitive, and a reference may be typed either way");
        assertTrue(runtime.authFor("alpine:3.20").isEmpty(), "Docker Hub is not this registry");
        assertTrue(runtime.authFor("ghcr.io/acme/agent:1").isEmpty());
        assertTrue(runtime.authFor("acme/agent:1").isEmpty(),
                "a Hub namespace that happens to read like a host must not match one");
    }

    /** With no credential configured every pull is anonymous, which is the ordinary deployment. */
    @Test
    void anUnconfiguredRuntimePullsAnonymously() {
        assertTrue(new DockerRunRuntime().authFor("registry.acme.example/team/agent:1").isEmpty());
    }
}