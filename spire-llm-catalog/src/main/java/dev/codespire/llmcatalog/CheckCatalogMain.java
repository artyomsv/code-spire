package dev.codespire.llmcatalog;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * The catalog check CI runs: {@code <head> [<base>]}. The head file is parsed strictly and
 * validated; with a base (the file on master), the change rules apply too. Exit 1 with every
 * problem printed. A missing base file means there is no published catalog yet.
 */
public final class CheckCatalogMain {

    private CheckCatalogMain() {
    }

    public static void main(String[] args) throws IOException {
        if (args.length < 1 || args.length > 2) {
            System.err.println("usage: CheckCatalogMain <head catalog> [<base catalog>]");
            System.exit(2);
        }
        List<String> problems = check(Path.of(args[0]), args.length == 2 ? Path.of(args[1]) : null);
        if (problems.isEmpty()) {
            System.out.println("catalog " + args[0] + ": valid");
            return;
        }
        problems.forEach(problem -> System.err.println("catalog problem: " + problem));
        System.exit(1);
    }

    static List<String> check(Path headFile, Path baseFile) throws IOException {
        CatalogDocument head;
        try {
            head = CatalogParser.parse(Files.readString(headFile, StandardCharsets.UTF_8), CatalogParser.Mode.STRICT);
        } catch (CatalogException e) {
            return List.of(e.getMessage());
        }
        List<String> problems = new ArrayList<>(CatalogValidator.problems(head, LocalDate.now(ZoneOffset.UTC)));
        CatalogDocument base = readBase(baseFile);
        if (base != null) {
            problems.addAll(CatalogChangeRules.problems(base, head));
        }
        return problems;
    }

    /** The published file is read leniently: it was valid when merged, perhaps under an older format. */
    private static CatalogDocument readBase(Path baseFile) throws IOException {
        if (baseFile == null || !Files.isRegularFile(baseFile)) {
            return null;
        }
        try {
            return CatalogParser.parse(Files.readString(baseFile, StandardCharsets.UTF_8), CatalogParser.Mode.LENIENT);
        } catch (CatalogException e) {
            System.err.println("the base catalog could not be read (" + e.getMessage() + "); change rules skipped");
            return null;
        }
    }
}
