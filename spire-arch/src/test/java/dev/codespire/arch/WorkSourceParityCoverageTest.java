package dev.codespire.arch;

import org.junit.jupiter.api.Test;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** Adapter directories are discovered independently of the test classes that claim coverage. */
class WorkSourceParityCoverageTest {
    private static final String PREFIX = "spire-worksource-";
    private static final Pattern CONTRACT = Pattern.compile(
            "(?m)^\\s*(?:public\\s+)?class\\s+\\w+\\s+extends\\s+WorkSourceParityCases\\s*\\{");
    private static final Pattern ARM = Pattern.compile(
            "WorkSourceType\\s+type\\(\\)\\s*\\{\\s*return\\s+WorkSourceType\\.(\\w+)\\s*;");

    @Test void everyWorkSourceModuleRunsTheSharedParityContract() throws IOException {
        Set<String> modules = new TreeSet<>();
        try (var directories = Files.list(RootBuild.repoRoot())) {
            directories.filter(Files::isDirectory).map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith(PREFIX))
                    .forEach(name -> modules.add(name.substring(PREFIX.length()).toUpperCase(Locale.ROOT).replace('-', '_')));
        }
        assertFalse(modules.isEmpty(), "No work-source adapters were discovered; the scan would prove nothing");
        Set<String> covered = new TreeSet<>();
        Path testRoot = RootBuild.repoRoot().resolve("spire-orchestrator/src/test/java");
        try (var files = Files.walk(testRoot)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                String code = JavaSource.withoutComments(Files.readString(file));
                if (!CONTRACT.matcher(code).find()) continue;
                var arm = ARM.matcher(code);
                assertTrue(arm.find(), "A parity subclass must identify its source type: " + file);
                assertTrue(code.contains("@QuarkusTest"), "A parity subclass must actually run its service cases: " + file);
                covered.add(arm.group(1));
            }
        }
        assertEquals(modules, covered,
                "Every discovered work-source arm must extend WorkSourceParityCases; a parallel copy does not inherit new cases");
    }
}
