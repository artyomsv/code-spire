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
 * appears here, the statelessness ADR-039 rests on has been lost". Nothing checked it. When the two
 * hand-rolled credential scrubbers were merged into one home, that home had to be a module both the
 * worker and the publisher could depend on, and the publisher depends on {@code spire-workspace} and
 * nothing else. So the dependency now exists, and the invariant needed a form that survives it.
 *
 * <p>The rule is therefore sharper than the comment it replaces: the worker may import
 * {@link #ALLOWED} from that module and nothing else. Adding {@code PublishRepo} or
 * {@code WorkspaceClone} fails the build with the reason, which the missing dependency could only
 * ever do by accident.
 */
class RunWorkerRunsNoGitTest {

    private static final String MODULE = "spire-run-worker";

    private static final String GIT_PACKAGE = "dev.codespire.workspace";

    /**
     * The types the worker may take from the git module.
     *
     * <p>{@code SecretScrub} is text handling — three encodings of a credential, replaced in a
     * string. It touches no repository and opens no file. It lives in that module because the worker
     * and the publisher share no other, and because that module already owns {@code GitCredential}.
     *
     * <p>Every other type there reaches a repository on disk, which is the thing this guard exists to
     * keep out. Adding an entry means arguing that a second type carries no working copy with it.
     */
    private static final Set<String> ALLOWED = Set.of("SecretScrub");

    /**
     * {@code import dev.codespire.workspace.Foo;}, and the wildcard form, which would otherwise let
     * every type in under one line that names none of them.
     */
    private static final Pattern IMPORT = Pattern.compile(
            "import\\s+(?:static\\s+)?" + Pattern.quote(GIT_PACKAGE) + "\\.([A-Za-z_$][\\w$]*)");

    @Test
    void theWorkerTakesNothingFromTheGitModuleButTheScrubber() {
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
     * Guards the guard.
     *
     * <p>A wrong module path makes the scan find no files, and a scan of nothing reports no leak.
     * The allowed import must also actually be there: if {@code SecretScrub} moves again, this
     * entry is stale and the next reader should be told, not left with a rule about a type the
     * worker no longer uses.
     */
    @Test
    void theScanReachesTheWorkerAndFindsTheOneImportItPermits() {
        List<Path> sources = workerSources();
        assertTrue(sources.size() > 20, "expected the worker's sources, scanned only " + sources.size());

        boolean scrubberImported = sources.stream()
                .map(RunWorkerRunsNoGitTest::read)
                .anyMatch(code -> code.contains("import " + GIT_PACKAGE + ".SecretScrub;"));
        assertTrue(scrubberImported,
                "no file imports " + GIT_PACKAGE + ".SecretScrub, so this guard is asserting a rule "
                        + "about an import that no longer exists — has the scrubber moved again?");
    }

    /** {@code dev.codespire.workspace.Foo} written out in full, which needs no import. */
    private static List<String> qualifiedUses(String code) {
        List<String> types = new ArrayList<>();
        Matcher inline = Pattern.compile(Pattern.quote(GIT_PACKAGE) + "\\.([A-Za-z_$][\\w$]*)")
                .matcher(code);
        while (inline.find()) {
            // The import form is matched above and would otherwise be reported twice, once as an
            // import and once as the qualified name inside it.
            if (!code.startsWith("import", lineStart(code, inline.start()))) {
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
