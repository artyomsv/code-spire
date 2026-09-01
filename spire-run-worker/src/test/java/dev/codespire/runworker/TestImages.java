package dev.codespire.runworker;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The three images the M0 walking-skeleton test needs, built once per JVM through the docker CLI.
 *
 * <p>The CLI rather than docker-java, deliberately: the test only builds, runs, inspects and execs,
 * and every host that has the daemon has the CLI. The publisher image is built from the module's
 * own Dockerfile against the installed distribution ({@code :spire-publisher:installDist}, a task
 * dependency of this module's tests), under the exact tag {@code RunUnitBuilder} names — so what
 * the test exercises is the image a deployment would run.
 */
final class TestImages {

    /** What RunUnitBuilder names. Built here from spire-publisher/Dockerfile. */
    static final String PUBLISHER = "spire-publisher:latest";

    /** alpine + git + the reference entrypoint: a shell script stands in for the model. */
    static final String AGENT = "spire-test-agent:m0";

    /** A smart-HTTP git remote behind basic auth, seeded with one commit. */
    static final String ORIGIN = "spire-test-origin:m0";

    private static boolean built;

    private TestImages() {
    }

    static Path repoRoot() {
        String root = System.getProperty("spire.repoRoot");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("spire.repoRoot is unset — see spire-run-worker/build.gradle.kts");
        }
        return Path.of(root);
    }

    static synchronized void buildAll() throws IOException {
        if (built) {
            return;
        }
        Path root = repoRoot();
        Path testDocker = root.resolve("spire-run-worker/src/test/docker");

        docker("build", "-q", "-t", PUBLISHER, root.resolve("spire-publisher").toString());

        Path agentContext = Files.createTempDirectory("spire-m0-agent-");
        Files.copy(testDocker.resolve("agent/Dockerfile"), agentContext.resolve("Dockerfile"));
        copyAsLf(root.resolve("deploy/agent/spire-agent-entrypoint.sh"),
                agentContext.resolve("spire-agent-entrypoint.sh"));
        docker("build", "-q", "-t", AGENT, agentContext.toString());

        Path originContext = Files.createTempDirectory("spire-m0-origin-");
        Files.copy(testDocker.resolve("origin/Dockerfile"), originContext.resolve("Dockerfile"));
        Files.copy(testDocker.resolve("origin/nginx.conf"), originContext.resolve("nginx.conf"));
        copyAsLf(testDocker.resolve("origin/entrypoint.sh"), originContext.resolve("entrypoint.sh"));
        docker("build", "-q", "-t", ORIGIN, originContext.toString());

        built = true;
    }

    /** A shell script with a CRLF shebang does not run; a Windows checkout can produce one. */
    private static void copyAsLf(Path from, Path to) throws IOException {
        Files.writeString(to, Files.readString(from, StandardCharsets.UTF_8).replace("\r\n", "\n"));
    }

    /** Runs the docker CLI; returns trimmed stdout; throws with stderr on a non-zero exit. */
    static String docker(String... args) {
        List<String> argv = new ArrayList<>();
        argv.add("docker");
        argv.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(argv).start();
            String out = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            String err = new String(process.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(15, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("docker " + String.join(" ", args) + " did not finish");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("docker " + String.join(" ", args) + " exited "
                        + process.exitValue() + ": " + err.strip());
            }
            return out.strip();
        } catch (IOException e) {
            throw new IllegalStateException("docker " + String.join(" ", args) + " could not start", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running docker " + String.join(" ", args), e);
        }
    }

    /** Exit code only, for probes that are allowed to fail. */
    static int dockerStatus(String... args) {
        List<String> argv = new ArrayList<>();
        argv.add("docker");
        argv.addAll(List.of(args));
        try {
            Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            process.getInputStream().readAllBytes();
            return process.waitFor();
        } catch (IOException e) {
            throw new IllegalStateException("docker " + String.join(" ", args) + " could not start", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted", e);
        }
    }
}
