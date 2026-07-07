package dev.codespire.diff;

import java.util.Locale;
import java.util.Map;

/** File-extension -> language tag, used for prompt hints and metadata. */
public final class Languages {

    private static final Map<String, String> BY_EXTENSION = Map.ofEntries(
            Map.entry("java", "java"), Map.entry("kt", "kotlin"), Map.entry("kts", "kotlin"),
            Map.entry("ts", "typescript"), Map.entry("tsx", "typescript"),
            Map.entry("js", "javascript"), Map.entry("jsx", "javascript"),
            Map.entry("py", "python"), Map.entry("go", "go"), Map.entry("rs", "rust"),
            Map.entry("rb", "ruby"), Map.entry("cs", "csharp"), Map.entry("cpp", "cpp"),
            Map.entry("c", "c"), Map.entry("h", "c"), Map.entry("sql", "sql"),
            Map.entry("sh", "shell"), Map.entry("yml", "yaml"), Map.entry("yaml", "yaml"),
            Map.entry("json", "json"), Map.entry("xml", "xml"), Map.entry("html", "html"),
            Map.entry("css", "css"), Map.entry("md", "markdown"), Map.entry("tf", "terraform"),
            Map.entry("scala", "scala"), Map.entry("swift", "swift"), Map.entry("php", "php"));

    private Languages() {
    }

    public static String of(String path) {
        if (path == null) {
            return "unknown";
        }
        int dot = path.lastIndexOf('.');
        if (dot < 0 || dot == path.length() - 1) {
            return "unknown";
        }
        // Locale.ROOT: the default locale must not change extension mapping (Turkish-I)
        return BY_EXTENSION.getOrDefault(path.substring(dot + 1).toLowerCase(Locale.ROOT), "unknown");
    }
}
