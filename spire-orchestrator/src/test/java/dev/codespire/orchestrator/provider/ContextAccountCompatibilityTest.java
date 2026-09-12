package dev.codespire.orchestrator.provider;

import org.junit.jupiter.api.Test;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import static org.junit.jupiter.api.Assertions.*;

/** The UI picker and the registry must agree across their language boundary. */
class ContextAccountCompatibilityTest {
    @Test void pickerKindsMatchTheCompositionRoot() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        while (!Files.exists(root.resolve("spire-ui"))) root = root.getParent();
        String source = Files.readString(root.resolve("spire-ui/src/components/SettingsContextProviders.tsx"));
        String table = source.substring(source.indexOf("export const compatibleAccountKinds:"));
        table = table.substring(0, table.indexOf("};"));
        var rows = Pattern.compile("(?:'([^']+)'|(\\w+)):\\s*\\[([^]]*)]").matcher(table);
        var actual = new HashMap<String, Set<String>>();
        while (rows.find()) {
            var kinds = new HashSet<String>();
            var names = Pattern.compile("'([^']+)'").matcher(rows.group(3));
            while (names.find()) kinds.add(names.group(1));
            actual.put(rows.group(1) == null ? rows.group(2) : rows.group(1), kinds);
        }
        assertEquals(Set.of("jira", "confluence", "github-issues", "gitlab-issues", "code"), actual.keySet());
        for (var row : actual.entrySet()) {
            var expected = new HashSet<String>();
            for (String kind : ProviderClients.ACCOUNT_TYPES) {
                if (ProviderClients.supportsContext(row.getKey(), kind)) expected.add(kind);
            }
            assertEquals(expected, row.getValue(), row.getKey());
        }
    }
}
