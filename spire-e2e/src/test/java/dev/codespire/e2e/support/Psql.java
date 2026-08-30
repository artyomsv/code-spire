package dev.codespire.e2e.support;

import java.io.File;
import java.io.IOException;
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

    /**
     * ASCII record separator, so ROWS are delimited by something a value cannot contain.
     *
     * <p>Splitting on newline first looked fine and was wrong in the PASSING direction: a value
     * containing a newline — a finding message, a note body, an error text — became two rows, so a
     * count came out too high and nothing failed. No current caller selects such a column, but
     * {@link #rows} is a generic helper and the next caller would have found out the hard way.
     */
    private static final String RECORD_SEPARATOR = "";

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
                "postgres",
                "psql", "-U", required("POSTGRES_USER"), "-d", required("POSTGRES_DB"),
                "-tA", "-F", SEPARATOR, "-R", RECORD_SEPARATOR, "-c", sql);

        List<List<String>> rows = new ArrayList<>();
        for (String record : run(command).split(RECORD_SEPARATOR, -1)) {
            String row = trimLineTerminators(record);
            // A row whose only column is '' or NULL prints as nothing, and psql also writes a newline
            // after the last record. Distinguishing them is why the blank check is not just
            // `isEmpty()`: ReadModel.prState on a NULL column would otherwise report "the row is
            // missing" when the row exists and the value is null.
            if (row.isEmpty() && !record.contains(SEPARATOR)) {
                continue;
            }
            rows.add(List.of(row.split(SEPARATOR, -1)));
        }
        return rows;
    }

    /**
     * Trims newlines only — NOT {@link String#strip()}.
     *
     * <p>Java counts the ASCII separators 0x1C-0x1F as whitespace, so {@code strip()} silently ate the
     * leading unit separator of a row whose first column was empty, turning {@code ['', 'x']} into
     * {@code ['x']} and shifting every later column. The record separator this splits on is 0x1E, and
     * the column separator is 0x1F, so a whitespace-based trim is exactly the wrong tool here.
     */
    private static String trimLineTerminators(String record) {
        int start = 0;
        int end = record.length();
        while (start < end && (record.charAt(start) == '\n' || record.charAt(start) == '\r')) {
            start++;
        }
        while (end > start && (record.charAt(end - 1) == '\n' || record.charAt(end - 1) == '\r')) {
            end--;
        }
        return record.substring(start, end);
    }

    private static String run(List<String> command) {
        ProcessBuilder builder = new ProcessBuilder(command)
                .directory(repoRoot())
                .redirectErrorStream(true);
        // On the CHILD's environment, not in argv. `docker compose exec` forwards it, and a password
        // passed as a command-line argument is readable in `ps` by any other user on the machine for
        // the life of the call. A fixture password here, but the fix costs nothing.
        builder.environment().put("PGPASSWORD", required("POSTGRES_PASSWORD"));

        try {
            Process process = builder.start();
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!process.waitFor(60, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("psql timed out. Output so far: " + output);
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("psql exited " + process.exitValue() + ": " + output);
            }
            return output;
        } catch (IOException e) {
            throw new IllegalStateException("could not run psql — is the e2e stack up?", e);
        } catch (InterruptedException e) {
            // Restore the flag rather than swallowing it, as every other class in this module does.
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted running psql", e);
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
