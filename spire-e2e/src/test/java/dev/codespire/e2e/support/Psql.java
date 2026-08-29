package dev.codespire.e2e.support;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Reads the read model through {@code docker compose exec postgres psql}, as deploy/e2e.sh:135-139
 * already does.
 *
 * <p>Postgres has no published host port in deploy/compose.yml, and publishing one from the e2e
 * overlay would weaken the claim that the overlay only adds. The cost of that choice lives here:
 * results arrive as text, so every caller gets a small typed read (see {@link ReadModel}) rather than
 * matching strings at the call site.
 *
 * <p>The superuser from deploy/.env is used deliberately — it can read both the {@code orchestrator}
 * and {@code worker} schemas, which the per-service roles cannot, and this harness asserts across
 * both.
 */
public final class Psql {

    /**
     * ASCII unit separator. A tab or a pipe would be ambiguous: finding messages routinely contain
     * both, and a split on either would silently shift every later column.
     */
    private static final String SEPARATOR = "";

    private Psql() {
    }

    /** @return the single value of a single-row, single-column query. */
    public static String one(String sql) {
        List<List<String>> rows = rows(sql);
        if (rows.size() != 1 || rows.getFirst().size() != 1) {
            throw new IllegalStateException("expected exactly one value from `" + sql + "`, got " + rows);
        }
        return rows.getFirst().getFirst();
    }

    public static List<List<String>> rows(String sql) {
        List<String> command = List.of(
                "docker", "compose",
                "-f", "deploy/compose.yml",
                "-f", "deploy/compose.e2e.yml",
                "--env-file", "deploy/.env",
                "exec", "-T",
                "-e", "PGPASSWORD=" + required("POSTGRES_PASSWORD"),
                "postgres",
                "psql", "-U", required("POSTGRES_USER"), "-d", required("POSTGRES_DB"),
                "-tA", "-F", SEPARATOR, "-c", sql);

        String output = run(command);
        List<List<String>> rows = new ArrayList<>();
        for (String line : output.split("\\R")) {
            if (line.isBlank()) {
                continue;
            }
            rows.add(List.of(line.split(SEPARATOR, -1)));
        }
        return rows;
    }

    private static String run(List<String> command) {
        try {
            Process process = new ProcessBuilder(command)
                    .directory(repoRoot())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("psql timed out. Output so far: " + output);
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("psql exited " + process.exitValue() + ": " + output);
            }
            return output;
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("could not run psql — is the e2e stack up?", e);
        }
    }

    private static File repoRoot() {
        String root = System.getProperty("spire.repoRoot");
        if (root == null || root.isBlank()) {
            throw new IllegalStateException("spire.repoRoot is unset — the Gradle test task must pass it "
                    + "(see spire-e2e/build.gradle.kts)");
        }
        return new File(root);
    }

    private static String required(String name) {
        String value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(name + " is unset. Source deploy/.env before running: "
                    + "`set -a; . deploy/.env; set +a`");
        }
        return value;
    }
}
