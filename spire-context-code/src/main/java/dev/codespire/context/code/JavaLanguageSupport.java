package dev.codespire.context.code;

import dev.codespire.contract.port.LanguageSupport;
import dev.codespire.contract.scm.DiffLine;
import dev.codespire.contract.scm.FilePatch;
import dev.codespire.contract.scm.Hunk;
import dev.codespire.contract.scm.LineType;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java's half of the repository knowledge base: which identifiers a patch's changed lines mention,
 * which imports a Java file declares, and which repository paths an import could resolve to.
 *
 * <p>Framework-free by design (see {@code spire-context-code}'s build file) — JDK regex only, no
 * parser dependency. A real Java parser would be more precise, but this module's job is a cheap,
 * conservative signal for context resolution, not a compiler front end.
 */
public final class JavaLanguageSupport implements LanguageSupport {

    /**
     * Tokens that are never a symbol worth resolving, so they never become an identifier to look up.
     *
     * <p>Every reserved word the language has, plus the contextual ones ({@code var}, {@code yield},
     * {@code sealed}, {@code permits}) and {@code String} — not a keyword, but by a wide margin the
     * most common token on a Java line that could never resolve to a repository file. The list was
     * partial, and a partial list is not merely untidy here: each surviving token is tried against
     * every resolved definition file, so {@code var}, {@code switch}, {@code case}, {@code break} and
     * friends inflated the quadratic extraction loop on <em>every</em> Java diff while resolving
     * nothing (M1/M2, PR 63 review).
     */
    private static final Set<String> KEYWORDS = Set.of(
            "abstract", "assert", "boolean", "break", "byte", "case", "catch", "char", "class",
            "const", "continue", "default", "do", "double", "else", "enum", "extends", "final",
            "finally", "float", "for", "goto", "if", "implements", "import", "instanceof", "int",
            "interface", "long", "native", "new", "package", "private", "protected", "public",
            "return", "short", "static", "strictfp", "super", "switch", "synchronized", "this",
            "throw", "throws", "transient", "try", "void", "volatile", "while",
            // Contextual keywords and the one ubiquitous type — see the javadoc above.
            "record", "sealed", "permits", "var", "yield", "String",
            // Literals, which the identifier regex also matches.
            "true", "false", "null");

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private static final Pattern LINE_COMMENT = Pattern.compile("//.*$");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");

    private static final Pattern IMPORT_LINE =
            Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w.]+)\\s*;");

    /** A declared type: {@code class}/{@code interface}/{@code enum}/{@code record} plus its name. */
    private static final Pattern TYPE_DECLARATION =
            Pattern.compile("\\b(?:class|interface|enum|record)\\s+([A-Za-z_$][A-Za-z0-9_$]*)");

    /**
     * Words that take an argument list without declaring anything, so a line containing them is a
     * call or a control-flow construct rather than a declaration.
     */
    private static final Set<String> NOT_DECLARATIONS = Set.of(
            "if", "for", "while", "switch", "catch", "return", "new", "synchronized", "assert",
            "super", "this", "do", "else", "try", "throw", "case", "instanceof");

    private static final Pattern PACKAGE_LINE = Pattern.compile("^\\s*package\\s+");

    private static final String JAVA_SOURCE_ROOT = "src/main/java/";

    @Override
    public Set<String> languages() {
        return Set.of("java");
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

    /**
     * A file's declared types and members, and the identifiers it merely mentions.
     *
     * <p>Regex rather than a parser, for the same reason the rest of this class is: the index only
     * produces CANDIDATES, which the caller re-fetches and confirms before citing, so being
     * occasionally wrong costs a wasted fetch rather than a wrong finding (ADR-026 §7.1). A parser
     * would buy precision this design does not need and a dependency it does not want.
     *
     * <p>Imported names are excluded from {@code references} deliberately. They say what the file
     * COULD use, not what it does, and rung 1 already follows imports in the other direction — an
     * import-derived reference would make every importer look like a caller of everything its
     * imports declare.
     */
    @Override
    public Symbols symbolsIn(String fileContent) {
        if (fileContent == null || fileContent.isBlank()) {
            return Symbols.NONE;
        }
        if (SourceText.tooLargeToScan(fileContent)) {
            return Symbols.NONE;
        }
        Set<String> defines = new LinkedHashSet<>();
        Set<String> references = new LinkedHashSet<>();
        for (String rawLine : SourceText.stripBlockComments(fileContent).split("\\R")) {
            String line = stripCommentsAndStrings(rawLine);
            scanLine(line, defines, references);
        }
        references.removeAll(defines);
        return new Symbols(defines, references);
    }

    /**
     * One line's declarations and references.
     *
     * <p><b>Imported names are deliberately kept as references.</b> An earlier version excluded
     * them, reasoning that an import says what a file COULD use rather than what it does — which is
     * wrong here and quietly made the whole feature inert. To call {@code Pricer.chargeFor()} a file
     * must import {@code Pricer}, so the filter removed the name precisely from the files that are
     * callers, and {@code callersOf("Pricer")} returned nothing. The import STATEMENTS are skipped
     * below, which is the guard that was actually wanted; the filter on top of it removed only
     * genuine body uses.
     */
    private void scanLine(String line, Set<String> defines, Set<String> references) {
        Matcher type = TYPE_DECLARATION.matcher(line);
        if (type.find()) {
            defines.add(type.group(1));
        }
        String callable = SourceText.declaredCallableName(line, NOT_DECLARATIONS);
        if (callable != null && !KEYWORDS.contains(callable)) {
            defines.add(callable);
        }
        if (IMPORT_LINE.matcher(line).find() || PACKAGE_LINE.matcher(line).find()) {
            return;
        }
        Matcher identifier = IDENTIFIER.matcher(line);
        while (identifier.find()) {
            String candidate = identifier.group();
            if (!KEYWORDS.contains(candidate)) {
                references.add(candidate);
            }
        }
    }

    private static String stripCommentsAndStrings(String content) {
        String withoutStrings = STRING_LITERAL.matcher(content).replaceAll(" ");
        return LINE_COMMENT.matcher(withoutStrings).replaceAll(" ");
    }

    @Override
    public List<ImportRef> importsIn(String fileContent) {
        List<ImportRef> imports = new ArrayList<>();
        for (String line : fileContent.split("\\R")) {
            Matcher matcher = IMPORT_LINE.matcher(line);
            if (!matcher.find()) {
                continue;
            }
            boolean isStatic = matcher.group(1) != null;
            String path = matcher.group(2);
            if (path.endsWith(".*")) {
                continue;
            }
            int lastDot = path.lastIndexOf('.');
            String symbol = lastDot < 0 ? path : path.substring(lastDot + 1);
            // A static import's member (the symbol) sits below the class it is imported from,
            // so the specifier is the class path with that trailing member segment removed.
            String specifier = isStatic && lastDot >= 0 ? path.substring(0, lastDot) : path;
            imports.add(new ImportRef(specifier, Set.of(symbol)));
        }
        return imports;
    }

    @Override
    public List<String> candidatePaths(ImportRef ref, String importingPath) {
        String relative = ref.specifier().replace('.', '/') + ".java";

        List<String> candidates = new ArrayList<>();
        int rootIndex = importingPath.indexOf(JAVA_SOURCE_ROOT);
        if (rootIndex >= 0) {
            String ownRoot = importingPath.substring(0, rootIndex + JAVA_SOURCE_ROOT.length());
            candidates.add(ownRoot + relative);
        }
        candidates.add(JAVA_SOURCE_ROOT + relative);
        candidates.add("src/test/java/" + relative);
        candidates.add(relative);
        return candidates;
    }
}
