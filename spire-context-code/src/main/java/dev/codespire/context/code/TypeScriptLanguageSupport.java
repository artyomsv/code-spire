package dev.codespire.context.code;

import dev.codespire.contract.port.LanguageSupport;
import dev.codespire.contract.scm.DiffLine;
import dev.codespire.contract.scm.FilePatch;
import dev.codespire.contract.scm.Hunk;
import dev.codespire.contract.scm.LineType;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * TypeScript and JavaScript's half of the repository knowledge base — {@link JavaLanguageSupport}'s
 * sibling for the two languages that share one module resolution scheme.
 *
 * <p>Framework-free by design (see {@code spire-context-code}'s build file) — JDK regex only, no
 * parser dependency. A real TS/JS parser would be more precise, but this module's job is a cheap,
 * conservative signal for context resolution, not a compiler front end.
 *
 * <p>{@code tsconfig.json} {@code paths} aliases are out of scope: an aliased import yields no
 * candidates from {@link #candidatePaths}, which is a recall gap, not an error.
 */
public final class TypeScriptLanguageSupport implements LanguageSupport {

    private static final Set<String> KEYWORDS = Set.of(
            "const", "let", "var", "function", "class", "return", "if", "else", "import", "from",
            "export", "default", "async", "await", "new", "this", "null", "true", "false",
            "interface", "type");

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private static final Pattern LINE_COMMENT = Pattern.compile("//.*$");

    // Single-, double-, and backtick-quoted literals. Stripped before comments are, for the same
    // reason as Java's sibling: a quoted "http://..." must not be truncated at the // it contains.
    private static final Pattern QUOTED_LITERAL = Pattern.compile(
            "'(?:\\\\.|[^'\\\\])*'|\"(?:\\\\.|[^\"\\\\])*\"|`(?:\\\\.|[^`\\\\])*`");

    private static final Pattern NAMED_IMPORT = Pattern.compile(
            "^\\s*import\\s+(?:(\\w+)\\s*,\\s*)?\\{([^}]*)}\\s+from\\s+['\"]([^'\"]+)['\"]");

    private static final Pattern DEFAULT_IMPORT =
            Pattern.compile("^\\s*import\\s+(\\w+)\\s+from\\s+['\"]([^'\"]+)['\"]");

    private static final Pattern NAMESPACE_IMPORT =
            Pattern.compile("^\\s*import\\s+\\*\\s+as\\s+(\\w+)\\s+from\\s+['\"]([^'\"]+)['\"]");

    private static final Pattern AS_ALIAS = Pattern.compile("\\s+as\\s+");

    @Override
    public Set<String> languages() {
        return Set.of("typescript", "javascript");
    }

    @Override
    public Set<String> identifiersIn(FilePatch patch) {
        Set<String> identifiers = new LinkedHashSet<>();
        List<Hunk> hunks = patch.hunks();
        if (hunks == null) {
            return identifiers;
        }
        for (Hunk hunk : hunks) {
            List<DiffLine> lines = hunk.lines();
            if (lines == null) {
                continue;
            }
            for (DiffLine line : lines) {
                if (line.type() != LineType.ADDED && line.type() != LineType.REMOVED) {
                    continue;
                }
                String stripped = stripCommentsAndStrings(line.content());
                Matcher matcher = IDENTIFIER.matcher(stripped);
                while (matcher.find()) {
                    String candidate = matcher.group();
                    if (!KEYWORDS.contains(candidate)) {
                        identifiers.add(candidate);
                    }
                }
            }
        }
        return identifiers;
    }

    private static String stripCommentsAndStrings(String content) {
        String withoutLiterals = QUOTED_LITERAL.matcher(content).replaceAll(" ");
        return LINE_COMMENT.matcher(withoutLiterals).replaceAll(" ");
    }

    @Override
    public List<ImportRef> importsIn(String fileContent) {
        List<ImportRef> imports = new ArrayList<>();
        for (String line : fileContent.split("\\R")) {
            Matcher named = NAMED_IMPORT.matcher(line);
            if (named.find()) {
                imports.add(namedImportRef(named));
                continue;
            }
            Matcher defaultImport = DEFAULT_IMPORT.matcher(line);
            if (defaultImport.find()) {
                imports.add(new ImportRef(defaultImport.group(2), Set.of(defaultImport.group(1))));
                continue;
            }
            Matcher namespaceImport = NAMESPACE_IMPORT.matcher(line);
            if (namespaceImport.find()) {
                imports.add(new ImportRef(namespaceImport.group(2), Set.of(namespaceImport.group(1))));
            }
            // Anything else — including a bindingless side-effect import like `import './x'` — has
            // nothing to intersect against identifiersIn, so it contributes no ImportRef.
        }
        return imports;
    }

    private static ImportRef namedImportRef(Matcher named) {
        String defaultName = named.group(1);
        String specifier = named.group(3);

        Set<String> symbols = new LinkedHashSet<>();
        if (defaultName != null) {
            symbols.add(defaultName);
        }
        for (String entry : named.group(2).split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            // `formatCost as money` binds the alias — that is the name the calling code actually uses.
            String[] parts = AS_ALIAS.split(trimmed);
            symbols.add(parts[parts.length - 1].trim());
        }
        return new ImportRef(specifier, symbols);
    }

    @Override
    public List<String> candidatePaths(ImportRef ref, String importingPath) {
        String specifier = ref.specifier();
        if (!specifier.startsWith(".")) {
            // A bare specifier (`react`) names a dependency, not repository code.
            return List.of();
        }

        String resolved = resolve(directoryOf(importingPath), specifier);
        return List.of(
                resolved + ".ts",
                resolved + ".tsx",
                resolved + ".js",
                resolved + "/index.ts",
                resolved + "/index.tsx");
    }

    private static String directoryOf(String path) {
        int lastSlash = path.lastIndexOf('/');
        return lastSlash < 0 ? "" : path.substring(0, lastSlash);
    }

    private static String resolve(String importingDir, String specifier) {
        Deque<String> segments = new ArrayDeque<>();
        if (!importingDir.isEmpty()) {
            segments.addAll(List.of(importingDir.split("/")));
        }
        for (String part : specifier.split("/")) {
            if (part.isEmpty() || part.equals(".")) {
                continue;
            }
            if (part.equals("..")) {
                if (!segments.isEmpty()) {
                    segments.removeLast();
                }
            } else {
                segments.addLast(part);
            }
        }
        return String.join("/", segments);
    }
}
