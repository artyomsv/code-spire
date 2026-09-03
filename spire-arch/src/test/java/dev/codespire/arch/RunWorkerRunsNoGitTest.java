package dev.codespire.arch;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * The run worker holds no git working copy, and this is what says so.
 *
 * <p>ADR-039 puts the clone in the init container and the push in the publisher, both inside the run
 * unit. The worker orchestrates and stores nothing: that is what lets a restarted replica recover a
 * run by discovery rather than by memory, and what makes the orphan watchdog's job possible at all. A
 * worker that grew a working copy would own state no other replica can see, and the run would belong
 * to the process that started it.
 *
 * <p><b>This replaces a comment.</b> {@code spire-run-worker/build.gradle.kts} carried a NOTE saying
 * the absence of a {@code spire-workspace} dependency was the tripwire — "if that dependency ever
 * appears here, the statelessness ADR-039 rests on has been lost". Nothing checked it, and a comment
 * is not a check.
 *
 * <p><b>The dependency was briefly added and then removed again, which is why this exists.</b> When
 * the two hand-rolled credential scrubbers were merged, the shared class first landed in
 * {@code spire-workspace} — the only module the publisher already depended on — and the worker took
 * a dependency on it for that one class. Review caught what a source scan structurally cannot:
 * {@code spire-workspace} exposes JGit as {@code api}, so the module whose entire claim is "runs no
 * git" had {@code org.eclipse.jgit} on its compile and runtime classpath. A scan refuses an IMPORT;
 * it cannot refuse a capability that is merely present. The scrubber moved again, to a JDK-only
 * {@code spire-secrets}, and the dependency went away.
 *
 * <p>So the rule is now the strongest of the three: the worker takes <b>nothing at all</b> from that
 * package. {@link #ALLOWED} is empty and should stay empty — an entry here is not merely an import,
 * it is a statement that whatever that module drags onto the classpath is acceptable in a process
 * that must hold no working copy.
 */
class RunWorkerRunsNoGitTest {

    private static final String MODULE = "spire-run-worker";

    private static final String GIT_PACKAGE = "dev.codespire.workspace";

    /**
     * The types the worker may take from the git module: <b>none</b>.
     *
     * <p>Kept as an empty set rather than deleted, because the check reads better as "the allowlist
     * is empty" than as a rule with no way to state an exception — and because the last exception
     * cost this project a JGit dependency on a process that must hold no working copy.
     *
     * <p>Adding an entry is not merely allowing an import. It is accepting whatever
     * {@code spire-workspace} puts on the classpath transitively, which is the part no source scan
     * can judge. If a type there is genuinely worth sharing, move it to a module that carries only
     * what it needs — {@code spire-secrets} is the worked example.
     */
    private static final Set<String> ALLOWED = Set.of();

    /**
     * {@code import dev.codespire.workspace.Foo;}, and the wildcard form, which would otherwise let
     * every type in under one line that names none of them.
     */
    private static final Pattern IMPORT = Pattern.compile(
            "import\\s+(?:static\\s+)?" + Pattern.quote(GIT_PACKAGE) + "\\.([A-Za-z_$][\\w$]*)");

    @Test
    void theWorkerTakesNothingFromTheGitModuleAtAll() {
        List<String> leaks = new ArrayList<>();
        for (Path source : workerSources()) {
            String code = JavaSource.withoutComments(read(source));
            Matcher found = IMPORT.matcher(code);
            while (found.find()) {
                String type = found.group(1);
                if (!ALLOWED.contains(type)) {
                    leaks.add(relative(source) + " imports " + GIT_PACKAGE + "." + type);
                }
            }
            // A wildcard names no type, so the matcher above cannot judge it. It admits every type
            // in the module, including the ones this guard exists to refuse.
            if (code.contains("import " + GIT_PACKAGE + ".*;")) {
                leaks.add(relative(source) + " imports " + GIT_PACKAGE + ".* — name the type instead");
            }
            // A fully-qualified reference needs no import at all, so an import-only scan would wave
            // `new dev.codespire.workspace.PublishRepo(...)` straight through.
            for (String qualified : qualifiedUses(code)) {
                if (!ALLOWED.contains(qualified)) {
                    leaks.add(relative(source) + " names " + GIT_PACKAGE + "." + qualified + " inline");
                }
            }
        }
        if (!leaks.isEmpty()) {
            fail(report(leaks));
        }
    }

    /**
     * Guards the guard: the scan must reach real files, and its detector must still detect.
     *
     * <p>A wrong module path makes the scan find no files, and a scan of nothing reports no leak.
     * That half is the file count.
     *
     * <p>The second half matters more now that {@link #ALLOWED} is empty, because an empty allowlist
     * makes "no leaks found" and "the detector is broken" produce the same green. So the pattern is
     * run against a line that MUST match, built here rather than read from the tree — a fixture the
     * scan cannot stop seeing.
     */
    @Test
    void theScanReachesTheWorkerAndItsDetectorStillDetects() {
        List<Path> sources = workerSources();
        assertTrue(sources.size() > 20, "expected the worker's sources, scanned only " + sources.size());

        assertTrue(IMPORT.matcher("import " + GIT_PACKAGE + ".PublishRepo;").find(),
                "the import pattern no longer matches an import, so every file now looks clean");
        assertTrue(IMPORT.matcher("import static " + GIT_PACKAGE + ".PublishRepo.open;").find(),
                "a static import must be caught too — it names the type just as plainly");
    }

    /** {@code dev.codespire.workspace.Foo} written out in full, which needs no import. */
    private static List<String> qualifiedUses(String code) {
        List<String> types = new ArrayList<>();
        Matcher inline = Pattern.compile(Pattern.quote(GIT_PACKAGE) + "\\.([A-Za-z_$][\\w$]*)")
                .matcher(code);
        while (inline.find()) {
            // The import form is matched above and would otherwise be reported twice, once as an
            // import and once as the qualified name inside it. The trailing space matters: without
            // it a line beginning `importantThing = new dev.codespire.workspace.PublishRepo()` reads
            // as an import and is skipped.
            if (!code.startsWith("import ", lineStart(code, inline.start()))) {
                types.add(inline.group(1));
            }
        }
        return types;
    }

    private static int lineStart(String code, int at) {
        int start = code.lastIndexOf('\n', at);
        int from = start < 0 ? 0 : start + 1;
        while (from < code.length() && Character.isWhitespace(code.charAt(from))) {
            from++;
        }
        return from;
    }

    private static String report(List<String> leaks) {
        return leaks.size() + " reference(s) from " + MODULE + " into " + GIT_PACKAGE + ":\n\n  "
                + String.join("\n  ", leaks)
                + """


                The run worker orchestrates runs; it does not hold a working copy. The clone lives in
                the init container and the push in the publisher, both inside the run unit (ADR-039).
                A worker with a repository on disk owns state no sibling replica can see, and the run
                then belongs to the process that started it — which is what the orphan watchdog and
                lease recovery are built to avoid.

                If the type you need really carries no repository with it, add it to ALLOWED with the
                argument. If it does, it belongs in the publisher.
                """;
    }

    private static List<Path> workerSources() {
        Path main = RootBuild.repoRoot().resolve(MODULE).resolve("src/main/java");
        if (!Files.isDirectory(main)) {
            return List.of();
        }
        try (Stream<Path> files = Files.walk(main)) {
            return files.filter(p -> p.toString().endsWith(".java")).sorted().toList();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String relative(Path source) {
        return RootBuild.repoRoot().relativize(source).toString().replace('\\', '/');
    }

    private static String read(Path source) {
        try {
            return Files.readString(source);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
