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
 * <p>{@link #importsIn} recognizes named (<code>{ a, b as c }</code>), bare default
 * (<code>import X from '...'</code>), namespace (<code>import * as ns from '...'</code>), and the
 * combined default-plus-named form (<code>import X, { a, b } from '...'</code>) — the last of these
 * binds the default name alongside the braced ones. Prettier commonly wraps a multi-symbol import
 * across several lines once it exceeds the print width, so a statement is first joined with its
 * continuation lines (see {@link #importStatementsIn}) before any of the four patterns are tried;
 * only where the text comes from changes, never what a matched form binds.
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

    // A statement's first line: "import" followed by at least one space, then more content — this
    // excludes both an unrelated identifier prefix ("importantValue") and "import.meta", neither of
    // which is an import statement.
    /** Block and JSDoc comments, across lines — DOTALL so a multi-line comment is one match. */
    private static final Pattern BLOCK_COMMENT = Pattern.compile("/\\*.*?\\*/", Pattern.DOTALL);

    /** A declared value or type: function, class, interface, type, enum, const/let/var. */
    private static final Pattern DECLARATION = Pattern.compile(
            "\\b(?:function|class|interface|type|enum|const|let|var)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    /** A class or object method: a name immediately followed by an argument list and a brace. */
    private static final Pattern METHOD_DECLARATION = Pattern.compile(
            "^\\s*(?:public\\s+|private\\s+|protected\\s+|static\\s+|async\\s+)*"
                    + "([A-Za-z_$][A-Za-z0-9_$]*)\\s*\\([^)]*\\)\\s*[:{]");

    private static final Pattern IMPORT_START = Pattern.compile("^\\s*import\\s+\\S");

    // The statement is complete once a `from` clause closes its quote — the specifier itself never
    // spans lines, only the braced symbol list (or the default name before it) does.
    private static final Pattern FROM_TERMINATED = Pattern.compile("from\\s*(['\"])[^'\"]*\\1");

    // A side-effect-only import (`import './x'`) has no `from` clause at all, so it terminates on
    // its own closing quote instead.
    private static final Pattern QUOTED_ONLY = Pattern.compile("^\\s*import\\s*(['\"])[^'\"]*\\1");

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

    /**
     * A module's declarations and the identifiers it mentions — rung 2's index input (ADR-026 §7).
     *
     * <p>Mirrors {@code JavaLanguageSupport}: imported names are excluded from references, because
     * an import says what the module COULD use and rung 1 already resolves imports in the other
     * direction. Regex rather than a parser for the same reason as the rest of this class — the
     * index yields candidates that are confirmed at citation, so imprecision costs a fetch.
     */
    @Override
    public Symbols symbolsIn(String fileContent) {
        if (fileContent == null || fileContent.isBlank()) {
            return Symbols.NONE;
        }
        Set<String> defines = new LinkedHashSet<>();
        Set<String> references = new LinkedHashSet<>();
        Set<String> imported = new LinkedHashSet<>();
        for (ImportRef ref : importsIn(fileContent)) {
            imported.addAll(ref.symbols());
        }
        String withoutBlockComments = BLOCK_COMMENT.matcher(fileContent).replaceAll(" ");
        for (String rawLine : withoutBlockComments.split("\\R")) {
            String line = stripCommentsAndStrings(rawLine);
            if (IMPORT_START.matcher(line).find()) {
                continue;
            }
            Matcher declaration = DECLARATION.matcher(line);
            if (declaration.find()) {
                defines.add(declaration.group(1));
            }
            Matcher method = METHOD_DECLARATION.matcher(line);
            if (method.find() && !KEYWORDS.contains(method.group(1))) {
                defines.add(method.group(1));
            }
            Matcher identifier = IDENTIFIER.matcher(line);
            while (identifier.find()) {
                String candidate = identifier.group();
                if (!KEYWORDS.contains(candidate) && !imported.contains(candidate)) {
                    references.add(candidate);
                }
            }
        }
        references.removeAll(defines);
        return new Symbols(defines, references);
    }

    @Override
    public List<ImportRef> importsIn(String fileContent) {
        List<ImportRef> imports = new ArrayList<>();
        for (String statement : importStatementsIn(fileContent)) {
            Matcher named = NAMED_IMPORT.matcher(statement);
            if (named.find()) {
                imports.add(namedImportRef(named));
                continue;
            }
            Matcher defaultImport = DEFAULT_IMPORT.matcher(statement);
            if (defaultImport.find()) {
                imports.add(new ImportRef(defaultImport.group(2), Set.of(defaultImport.group(1))));
                continue;
            }
            Matcher namespaceImport = NAMESPACE_IMPORT.matcher(statement);
            if (namespaceImport.find()) {
                imports.add(new ImportRef(namespaceImport.group(2), Set.of(namespaceImport.group(1))));
            }
            // Anything else — including a bindingless side-effect import like `import './x'` — has
            // nothing to intersect against identifiersIn, so it contributes no ImportRef.
        }
        return imports;
    }

    /**
     * One entry per {@code import} statement, continuation lines joined onto its first line with a
     * single space — so a braced symbol list Prettier wrapped across several lines reads as one
     * statement to the regexes above, exactly as it would if it had fit on one line.
     */
    private static List<String> importStatementsIn(String fileContent) {
        List<String> statements = new ArrayList<>();
        String[] lines = fileContent.split("\\R");
        int index = 0;
        while (index < lines.length) {
            if (!IMPORT_START.matcher(lines[index]).find()) {
                index++;
                continue;
            }
            StringBuilder statement = new StringBuilder(lines[index]);
            index++;
            while (!isTerminated(statement) && index < lines.length) {
                statement.append(' ').append(lines[index]);
                index++;
            }
            statements.add(statement.toString());
        }
        return statements;
    }

    private static boolean isTerminated(CharSequence statement) {
        return FROM_TERMINATED.matcher(statement).find() || QUOTED_ONLY.matcher(statement).find();
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
