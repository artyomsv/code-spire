package dev.codespire.arch;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The populated rollback column must have no runtime read or write references. */
class AccountWorkspaceIsUnusedTest {
    @Test void noProductionCodeReadsLegacyAccountWorkspace() throws Exception {
        List<String> violations = new ArrayList<>();
        int inspected = 0;
        try (var modules = Files.list(RootBuild.repoRoot())) {
            for (Path module : modules.filter(Files::isDirectory).toList()) {
                Path sources = module.resolve("src/main/java");
                if (!Files.isDirectory(sources)) continue;
                try (var paths = Files.walk(sources)) {
                    for (Path path : paths.filter(p -> p.toString().endsWith(".java")).toList()) {
                        inspected++;
                        String source = JavaSource.withoutComments(Files.readString(path));
                        String joined = source.replaceAll("\"\\s*\\+\\s*\"", "");
                        var insert = Pattern.compile("(?is)INSERT\\s+INTO\\s+(?:orchestrator\\.)?scm_provider\\s*\\(([^)]*)\\)").matcher(joined);
                        while (insert.find()) {
                            if (Pattern.compile("(?i)\\bworkspace\\b").matcher(insert.group(1)).find()) {
                                violations.add(path + ": inserts legacy account workspace");
                            }
                        }
                        var update = Pattern.compile("(?is)UPDATE\\s+(?:orchestrator\\.)?scm_provider\\b(?:\\s+(?:AS\\s+)?\\w+)?\\s+SET\\s+([^;\"{}]*?)(?:\\bWHERE\\b|\\bRETURNING\\b|[;\"])").matcher(joined);
                        while (update.find()) {
                            if (Pattern.compile("(?i)\\bworkspace\\b").matcher(update.group(1)).find()) {
                                violations.add(path + ": updates legacy account workspace");
                            }
                        }
                        var select = Pattern.compile("(?is)SELECT\\s+([^;\"{}]*?)\\s+FROM\\s+(?:orchestrator\\.)?scm_provider\\b").matcher(joined);
                        while (select.find()) {
                            if (Pattern.compile("(?i)\\bworkspace\\b|(?<![\\w.])\\*(?!\\w)").matcher(select.group(1)).find()
                                    && !select.group(1).strip().equalsIgnoreCase("COUNT(*)")) {
                                violations.add(path + ": selects legacy workspace or maps SELECT-star");
                            }
                        }
                        var alias = Pattern.compile("(?i)(?:FROM|JOIN)\\s+(?:orchestrator\\.)?scm_provider\\s+(?:AS\\s+)?(\\w+)").matcher(joined);
                        while (alias.find()) {
                            String name = alias.group(1);
                            if (Pattern.compile("(?i)\\b" + Pattern.quote(name) + "\\s*\\.\\s*(?:workspace\\b|\\*)").matcher(joined).find()) {
                                violations.add(path + ": reads account alias workspace or star");
                            }
                        }
                        String file = path.getFileName().toString();
                        if (List.of("ProviderInput.java", "ProviderView.java", "ScmProvider.java").contains(file)
                                && Pattern.compile("\\bString\\s+workspace\\b").matcher(source).find()) {
                            violations.add(path + ": account model carries workspace");
                        }
                        if (file.equals("ProviderRegistry.java") && source.contains("getString(\"workspace\")")) {
                            violations.add(path + ": account mapper reads workspace");
                        }
                        if (Pattern.compile("\\bresolveByWorkspace\\s*\\(").matcher(source).find()) {
                            violations.add(path + ": workspace-only resolver");
                        }
                    }
                }
            }
        }
        assertTrue(inspected > 100, "The architecture check must inspect the production tree");
        assertTrue(violations.isEmpty(), String.join("\n", violations));
    }
}
