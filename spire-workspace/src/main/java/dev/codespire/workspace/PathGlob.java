package dev.codespire.workspace;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * A glob matched against a git path, identically on every platform.
 *
 * <p><b>Deliberately not the JDK's {@code glob:} {@link java.nio.file.PathMatcher}</b>, which the
 * push gate's first draft used. That matcher works on {@link java.nio.file.Path}, so its behaviour
 * follows the filesystem the publisher happens to run on — and measurement showed three ways that
 * breaks a security gate:
 *
 * <ul>
 *   <li><b>It throws on paths git allows.</b> {@code Path.of("weird:name.yml")},
 *       {@code Path.of("a\\b.yml")} and {@code Path.of("trailing ")} all raise
 *       {@code InvalidPathException} on Windows, and all three are legal filenames a Linux
 *       repository can contain. A gate that cannot parse an input cannot judge it.</li>
 *   <li><b>Case sensitivity came from the filesystem, not the rule.</b> On Windows,
 *       {@code .GitHub/Workflows/ci.yml} matches {@code .github/workflows/**} natively — so the
 *       first draft's parallel lowercase matchers were dead weight there and the only thing
 *       enforcing the rule on Linux. The test for it passed on Windows with the guard deleted:
 *       vacuous on a developer's machine, load-bearing in CI.</li>
 *   <li><b>Separators differed.</b> A git path is always {@code /}-separated; a {@code Path} is
 *       whatever the platform says.</li>
 * </ul>
 *
 * <p>So the rule is expressed once, explicitly, over the string form git actually gives us. The
 * supported syntax is the subset the floor and a repository profile need — {@code **} crossing
 * separators, {@code *} and {@code ?} within one segment — and <b>anything else is refused at
 * compile time rather than treated as a literal</b>, because a profile glob that silently matches
 * nothing is a protection an operator believes they have.
 */
public final class PathGlob {

    private final String glob;

    private final Pattern pattern;

    private PathGlob(String glob, Pattern pattern) {
        this.glob = glob;
        this.pattern = pattern;
    }

    /**
     * @throws IllegalArgumentException on an unsupported construct, so a rule that could never
     *                                  match is a startup failure rather than a silent gap
     */
    public static PathGlob compile(String glob) {
        Objects.requireNonNull(glob, "glob");
        if (glob.isBlank()) {
            throw new IllegalArgumentException("a blank glob matches nothing and protects nothing");
        }
        return new PathGlob(glob, Pattern.compile(toRegex(glob), Pattern.CASE_INSENSITIVE));
    }

    public static List<PathGlob> compileAll(List<String> globs) {
        List<PathGlob> compiled = new ArrayList<>();
        for (String glob : globs) {
            compiled.add(compile(glob));
        }
        return List.copyOf(compiled);
    }

    /** @param path a git path: {@code /}-separated, no leading slash, never null */
    public boolean matches(String path) {
        return path != null && pattern.matcher(normalize(path)).matches();
    }

    /**
     * Backslashes become separators before matching.
     *
     * <p>Git itself stores {@code /}, but a path can reach here from a Windows working tree or a
     * hand-built {@link ChangedPath}, and {@code .github\workflows\ci.yml} is the same file as
     * {@code .github/workflows/ci.yml} to every forge that runs it.
     */
    private static String normalize(String path) {
        return path.replace('\\', '/');
    }

    private static String toRegex(String glob) {
        StringBuilder regex = new StringBuilder();
        int i = 0;
        while (i < glob.length()) {
            char c = glob.charAt(i);
            switch (c) {
                case '*' -> {
                    boolean doubled = i + 1 < glob.length() && glob.charAt(i + 1) == '*';
                    if (doubled) {
                        // Crosses separators: ".github/workflows/**" covers "a.yml" and "sub/a.yml".
                        regex.append(".*");
                        i++;
                    } else {
                        // Within one segment only.
                        regex.append("[^/]*");
                    }
                }
                case '?' -> regex.append("[^/]");
                case '{', '}', '[', ']' -> throw new IllegalArgumentException(
                        "unsupported glob construct '" + c + "' in \"" + glob
                                + "\" — only *, ** and ? are supported, and a rule that silently "
                                + "matched nothing would be a protection nobody has");
                default -> regex.append(Pattern.quote(String.valueOf(c)));
            }
            i++;
        }
        return regex.toString();
    }

    public String glob() {
        return glob;
    }

    @Override
    public String toString() {
        return "PathGlob[" + glob + "]";
    }
}
