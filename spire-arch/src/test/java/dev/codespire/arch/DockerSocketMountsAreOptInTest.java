package dev.codespire.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A compose service that mounts the host Docker socket must be behind a profile.
 *
 * <p><b>A Docker socket is root-equivalent on the host</b>, and {@code docs/SECURITY.md} says exactly
 * that under "What is NOT mitigated": the run worker drives the daemon directly, so a compromised
 * run worker is a compromised host — and the run worker is the service that executes untrusted model
 * output. That is not a reason to refuse the mount, which the Docker arm genuinely needs. It is a
 * reason for starting it to be an act an operator performs DELIBERATELY.
 *
 * <p>Compose profiles make that structural: a service carrying {@code profiles:} is not started by
 * {@code docker compose up}, only by {@code --profile factory up}. Delete that one line and the next
 * person who brings the stack up mounts their host's socket into a container that runs model output,
 * with nothing in the output saying so. There is no error, no warning, and no test — until this one.
 *
 * <p><b>Stated as a rule about the SOCKET, not about the run worker.</b> Naming the service would
 * guard today's file and miss the second service that ever needs a daemon. The check is: find every
 * socket mount, require a profile on the service that has it.
 */
class DockerSocketMountsAreOptInTest {

    /** Both packaged stacks. A service added to one and not the other is its own defect. */
    private static final List<String> COMPOSE_FILES =
            List.of("deploy/compose.yml", "deploy/compose.ghcr.yml");

    /** What makes a mount root-equivalent. Matched loosely so a rootless path is caught too. */
    private static final String SOCKET = "docker.sock";

    @Test
    void everyServiceMountingTheDockerSocketIsBehindAProfile() {
        List<String> violations = new ArrayList<>();
        for (String file : COMPOSE_FILES) {
            Map<String, String> services = servicesIn(repoRoot().resolve(file));
            assertFalse(services.isEmpty(), file + ": no services parsed, so this test measures nothing");

            services.forEach((name, body) -> {
                if (body.contains(SOCKET) && !body.contains("profiles:")) {
                    violations.add(file + " → " + name
                            + "\n      mounts the host Docker socket and is started by a plain "
                            + "`docker compose up`. That mount is root-equivalent on the host "
                            + "(docs/SECURITY.md). Put the service behind a profile.");
                }
            });
        }
        if (!violations.isEmpty()) {
            fail("A compose service mounts the Docker socket without opting in:\n\n  "
                    + String.join("\n\n  ", violations));
        }
    }

    /**
     * And the run worker really is one of them, in both files.
     *
     * <p>Without this, deleting the service entirely would leave the rule above vacuously satisfied —
     * a test that passes because there is nothing to check is the failure mode this repository has
     * hit repeatedly, and it passes loudest right after someone removes the thing it guards.
     */
    @Test
    void theRunWorkerIsPresentInBothStacksAndCarriesTheProfile() {
        for (String file : COMPOSE_FILES) {
            Map<String, String> services = servicesIn(repoRoot().resolve(file));

            assertTrue(services.containsKey("run-worker"),
                    file + " has no run-worker service; the socket rule above then guards nothing");
            String body = services.get("run-worker");
            assertTrue(body.contains("profiles:"), file + ": run-worker must be opt-in");
            assertTrue(body.contains("factory"), file + ": run-worker belongs to the factory profile");
            assertTrue(body.contains(SOCKET),
                    file + ": run-worker no longer mounts the socket — if that is deliberate, this "
                            + "test and the comments around the service both need rewriting");
        }
    }

    /** And no OTHER service acquired one quietly. Named so the count is a fact rather than a hope. */
    @Test
    void theRunWorkerIsTheOnlyServiceThatNeedsADaemon() {
        for (String file : COMPOSE_FILES) {
            List<String> withSocket = new ArrayList<>();
            servicesIn(repoRoot().resolve(file)).forEach((name, body) -> {
                if (body.contains(SOCKET)) {
                    withSocket.add(name);
                }
            });
            assertEquals(List.of("run-worker"), withSocket,
                    file + ": a second service now wants the host daemon. That may be right, but it "
                            + "is a security decision rather than a plumbing one — say so here.");
        }
    }

    /**
     * Top-level services, by indentation.
     *
     * <p>A text parse rather than a YAML library, which is the shape every other check in this module
     * uses and which avoids adding a dependency to a build-verification module. It is sufficient
     * because the property is coarse: a service's block is everything indented under its two-space
     * name until the next one.
     */
    private static Map<String, String> servicesIn(Path compose) {
        List<String> lines = read(compose).lines().toList();
        Map<String, String> services = new LinkedHashMap<>();
        boolean inServices = false;
        String current = null;
        StringBuilder body = new StringBuilder();

        for (String line : lines) {
            if (line.startsWith("services:")) {
                inServices = true;
                continue;
            }
            if (!inServices) {
                continue;
            }
            boolean topLevelKey = !line.isBlank() && !line.startsWith(" ");
            if (topLevelKey) {
                // `volumes:` or another root key ends the services block.
                break;
            }
            if (line.matches("^ {2}[A-Za-z0-9_.-]+:\\s*$")) {
                if (current != null) {
                    services.put(current, body.toString());
                }
                current = line.strip().replace(":", "");
                body = new StringBuilder();
                continue;
            }
            body.append(line).append('\n');
        }
        if (current != null) {
            services.put(current, body.toString());
        }
        return services;
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new UncheckedIOException("could not read " + path, e);
        }
    }

    /** The worktree root, found by walking up to the settings file rather than assuming a depth. */
    private static Path repoRoot() {
        Path here = Path.of("").toAbsolutePath();
        while (here != null && !Files.exists(here.resolve("settings.gradle.kts"))) {
            here = here.getParent();
        }
        if (here == null) {
            throw new IllegalStateException("could not find the repository root");
        }
        return here;
    }
}
