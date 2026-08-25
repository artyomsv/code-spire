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

    private static final Set<String> KEYWORDS = Set.of(
            "public", "private", "protected", "static", "final", "int", "long", "double", "boolean",
            "void", "class", "interface", "record", "new", "return", "if", "else", "for", "while",
            "this", "super", "null", "true", "false", "import", "package", "throws", "throw", "try",
            "catch");

    private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z_$][A-Za-z0-9_$]*");

    private static final Pattern LINE_COMMENT = Pattern.compile("//.*$");

    private static final Pattern STRING_LITERAL = Pattern.compile("\"(?:\\\\.|[^\"\\\\])*\"");

    private static final Pattern IMPORT_LINE =
            Pattern.compile("^\\s*import\\s+(static\\s+)?([\\w.]+)\\s*;");

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
