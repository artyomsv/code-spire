package dev.codespire.e2e.gitlab;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * {@code gitlab-rails runner} inside the GitLab container.
 *
 * <p>Used for exactly one thing the REST API cannot do: mint a personal access token whose VALUE we
 * choose. The API returns a token it generated, which is fine for a human and useless for a fixture —
 * the harness needs the token before it can make the call that would create it, and a value it picks
 * is also a value a failed run can be re-run against.
 *
 * <p>Each call boots a Rails environment and takes tens of seconds, so batch work into one script
 * rather than calling this in a loop.
 */
final class Rails {

    private Rails() {
    }

    static String run(String script) {
        List<String> command = List.of(
                "docker", "compose",
                "-f", "deploy/compose.yml",
                "-f", "deploy/compose.e2e.yml",
                "--env-file", "deploy/.env",
                "exec", "-T", "gitlab",
                "gitlab-rails", "runner", script);
        try {
            Process process = new ProcessBuilder(command)
                    .directory(repoRoot())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(5, TimeUnit.MINUTES)) {
                process.destroyForcibly();
                throw new IllegalStateException("gitlab-rails runner timed out. Output so far: " + output);
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(
                        "gitlab-rails runner exited " + process.exitValue() + ": " + output);
            }
            return output;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("gitlab-rails runner failed for script: " + script, e);
        }
    }

    private static File repoRoot() {
        String root = System.getProperty("spire.repoRoot");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("spire.repoRoot is unset — see spire-e2e/build.gradle.kts");
        }
        return new File(root);
    }
}
